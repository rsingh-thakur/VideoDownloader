package com.example.demo.controller;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class YoutubeController {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @GetMapping("/info")
    @ResponseBody
    public java.util.Map<String, String> getInfo(@RequestParam String url) {
        try {
            String ytDlpPath = System.getenv().getOrDefault("YT_DLP_PATH", "yt-dlp");
            ProcessBuilder builder = new ProcessBuilder(
                    ytDlpPath,
                    "--get-title",
                    "--get-thumbnail",
                    "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                    "-g",
                    "--no-playlist",
                    url);
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String title = reader.readLine();
            String thumbnail = reader.readLine();
            String videoUrl = reader.readLine();
            process.waitFor();
            
            java.util.Map<String, String> info = new java.util.HashMap<>();
            info.put("title", title);
            info.put("thumbnail", thumbnail);
            info.put("streamUrl", videoUrl);
            return info;
        } catch (Exception e) {
            return java.util.Collections.singletonMap("error", e.getMessage());
        }
    }

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/progress")
    @ResponseBody
    public SseEmitter streamProgress(
            @RequestParam String url,
            @RequestParam(defaultValue = "720") String quality,
            @RequestParam(defaultValue = "video") String type,
            @RequestParam(defaultValue = "false") boolean playlist) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                File downloadDir = new File("downloads");
                if (!downloadDir.exists()) downloadDir.mkdirs();

                boolean downloadVideo = type.equals("video") || type.equals("both");
                boolean downloadAudio = type.equals("audio") || type.equals("both");

                String lastFile = "";

                if (downloadVideo) {
                    lastFile = runYtDlp(url, quality, "video", playlist, emitter);
                }

                if (downloadAudio) {
                    String audioFile = runYtDlp(url, quality, "audio", playlist, emitter);
                    if (!downloadVideo) lastFile = audioFile;
                }

                emitter.send(SseEmitter.event().name("complete").data(lastFile));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private String runYtDlp(String url, String quality, String type, boolean allowPlaylist, SseEmitter emitter) throws Exception {
        String ytDlpPath = System.getenv().getOrDefault("YT_DLP_PATH", "yt-dlp");
        String ffmpegPath = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
        
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(ytDlpPath);

        if (type.equals("audio")) {
            command.add("-f"); command.add("ba");
            command.add("-x");
            command.add("--audio-format"); command.add("mp3");
        } else {
            String format = "bv[height<=" + quality + "][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b";
            command.add("-f"); command.add(format);
            command.add("--merge-output-format"); command.add("mp4");
        }

        if (!allowPlaylist) {
            command.add("--no-playlist");
        }

        command.add("--restrict-filenames");
        command.add("--ffmpeg-location"); command.add(ffmpegPath);
        command.add("--progress");
        command.add("--newline");
        command.add("-o"); command.add("downloads/%(title)s-%(id)s.%(ext)s");
        command.add(url);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        String line;
        Pattern percentPattern = Pattern.compile("\\[download\\]\\s+(\\d+\\.\\d+)%");
        Pattern titlePattern = Pattern.compile("\\[download\\] Destination: downloads/(.*)-.*\\.");
        String lastFile = "";
        String currentTitle = "Video";

        while ((line = reader.readLine()) != null) {
            System.out.println(line);
            
            Matcher titleMatcher = titlePattern.matcher(line);
            if (titleMatcher.find()) {
                currentTitle = titleMatcher.group(1);
            }

            Matcher percentMatcher = percentPattern.matcher(line);
            if (percentMatcher.find()) {
                String percent = percentMatcher.group(1);
                String json = String.format("{\"percent\": %s, \"title\": \"%s\"}", 
                                             percent, currentTitle.replace("\"", "\\\""));
                emitter.send(SseEmitter.event().name("progress").data(json));
            }
            
            if (line.contains("[download] Destination:")) {
                lastFile = line.substring(line.lastIndexOf("downloads")).trim();
            } else if (line.contains("Merging formats into")) {
                lastFile = line.substring(line.lastIndexOf("downloads")).trim();
            } else if (line.contains("[ExtractAudio] Destination:")) {
                lastFile = line.substring(line.lastIndexOf("downloads")).trim();
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("yt-dlp failed with code " + exitCode);

        return lastFile.replace("downloads/", "").replace("downloads\\", "");
    }
}