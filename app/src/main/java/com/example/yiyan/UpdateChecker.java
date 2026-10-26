package com.example.yiyan;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 GitHub Releases 检查并下载更新（不引第三方库，用 HttpURLConnection + org.json）。
 */
final class UpdateChecker {

    static final String REPO = "yingxingyswx-design/DailyLine_chinese";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    interface Progress {
        void onProgress(int percent, long downloaded, long total);
    }

    static final class Release {
        String tag = "";
        String name = "";
        String body = "";
        String apkUrl = "";
        long apkSize;
    }

    private UpdateChecker() {
    }

    /** 更新频道：GitHub Release。stable 只看正式发布，prerelease 连预发布一起看。 */
    static final String CHANNEL_STABLE = "stable";
    static final String CHANNEL_PRERELEASE = "prerelease";

    static Release fetchLatest(String channel) throws Exception {
        boolean includePrerelease = CHANNEL_PRERELEASE.equals(channel);
        try {
            return includePrerelease ? fetchFromList() : fetchFromApi();
        } catch (Exception apiError) {
            // 部分网络会拦掉 api.github.com 的 DNS，退回读 releases.atom（同一个 github.com 域名）
            try {
                return fetchFromAtom();
            } catch (Exception atomError) {
                throw new IOException(apiError.getMessage());
            }
        }
    }

    private static Release fetchFromApi() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "DailyLine-Android");
        int code = connection.getResponseCode();
        if (code != 200) throw new IOException("GitHub 返回 HTTP " + code);
        return parseRelease(new JSONObject(readAll(connection.getInputStream())));
    }

    /** 取最近若干条 Release，挑第一条非草稿（可能含预发布）。 */
    private static Release fetchFromList() throws Exception {
        String url = "https://api.github.com/repos/" + REPO + "/releases?per_page=10";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "DailyLine-Android");
        int code = connection.getResponseCode();
        if (code != 200) throw new IOException("GitHub 返回 HTTP " + code);

        JSONArray list = new JSONArray(readAll(connection.getInputStream()));
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || item.optBoolean("draft", false)) continue;
            return parseRelease(item);
        }
        throw new IOException("没有可用的发布记录");
    }

    private static Release parseRelease(JSONObject json) throws Exception {
        Release release = new Release();
        release.tag = json.optString("tag_name", "");
        release.name = json.optString("name", release.tag);
        release.body = json.optString("body", "");

        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String assetName = asset.optString("name", "");
                if (assetName.endsWith(".apk")) {
                    release.apkUrl = asset.optString("browser_download_url", "");
                    release.apkSize = asset.optLong("size", 0);
                    break;
                }
            }
        }
        if (release.tag.isEmpty()) throw new IOException("没有解析到版本号");
        return release;
    }

    /** 兜底：解析 releases.atom，第一条就是最新 release。 */
    private static Release fetchFromAtom() throws Exception {
        String atomUrl = "https://github.com/" + REPO + "/releases.atom";
        HttpURLConnection connection = (HttpURLConnection) new URL(atomUrl).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "DailyLine-Android");
        int code = connection.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);

        String xml = readAll(connection.getInputStream());
        int entry = xml.indexOf("<entry>");
        if (entry < 0) throw new IOException("没有发布记录");

        String tag = "";
        Matcher link = Pattern.compile("releases/tag/([^\"<]+)").matcher(xml);
        if (link.find(entry)) tag = link.group(1);
        if (tag.isEmpty()) throw new IOException("没有解析到版本号");

        Release release = new Release();
        release.tag = tag;
        Matcher title = Pattern.compile("<title>([^<]*)</title>").matcher(xml);
        if (title.find(entry)) release.name = title.group(1);
        release.apkUrl = "https://github.com/" + REPO + "/releases/download/" + tag + "/app-release.apk";
        return release;
    }

    /** 只比 major.minor，够用且避免误判。 */
    static boolean isNewer(String latestTag, String currentVersion) {
        int[] latest = parse(latestTag);
        int[] current = parse(currentVersion);
        if (latest[0] != current[0]) return latest[0] > current[0];
        return latest[1] > current[1];
    }

    private static int[] parse(String version) {
        Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)").matcher(version == null ? "" : version);
        if (matcher.find()) {
            return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        }
        return new int[]{0, 0};
    }

    static File download(Context context, Release release, Progress progress) throws Exception {
        if (release.apkUrl.isEmpty()) throw new IOException("该版本没有 APK 资源");
        File target = new File(context.getCacheDir(), "DailyLine-" + release.tag + ".apk");

        HttpURLConnection connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DailyLine-Android");
        int code = connection.getResponseCode();
        if (code != 200) throw new IOException("下载失败 HTTP " + code);

        long total = connection.getContentLengthLong() > 0
                ? connection.getContentLengthLong() : release.apkSize;
        InputStream in = connection.getInputStream();
        FileOutputStream out = new FileOutputStream(target);
        byte[] buffer = new byte[64 * 1024];
        long done = 0;
        int read;
        int lastPercent = -1;
        try {
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        if (progress != null) progress.onProgress(percent, done, total);
                    }
                }
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            out.close();
        }
        return target;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        in.close();
        return out.toString("UTF-8");
    }
}
