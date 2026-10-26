package com.example.yiyan;

/**
 * 一条一言。作者、出处、配图都可以为空（只写句子也是合法条目），只有 {@link #text} 必填。
 * 存储格式统一为 {@code 句子|作者|出处|分类|图片文件名}，见 {@link #toLine()} / {@link #parse(String, String, String)}。
 * 图片只存文件名，实际文件在应用私有目录 {@code files/quote_images/} 下，由 {@link ImageStore} 管理。
 */
public class Quote {

    public final String text, author, work, kind, category, imagePath;

    public Quote(String text, String author, String work, String kind) {
        this(text, author, work, kind, "哲学", "");
    }

    public Quote(String text, String author, String work, String kind, String category) {
        this(text, author, work, kind, category, "");
    }

    public Quote(String text, String author, String work, String kind, String category, String imagePath) {
        this.text = text == null ? "" : text;
        this.author = author == null ? "" : author;
        this.work = work == null ? "" : work;
        this.kind = kind == null ? "" : kind;
        this.category = category == null || category.trim().isEmpty() ? "用户" : category;
        this.imagePath = imagePath == null ? "" : imagePath;
    }

    /**
     * 解析一行存储数据。作者、出处、分类、配图都可缺省，只有句子必须非空。
     * 兼容 3 段（句子|作者|出处）、4 段、5 段三种历史格式。
     *
     * @return 解析失败（句子为空）时返回 null
     */
    public static Quote parse(String line, String kind, String defaultCategory) {
        if (line == null) return null;
        String[] fields = line.split("\\|", -1);
        String textPart = fields.length > 0 ? fields[0].trim() : "";
        if (textPart.isEmpty()) return null;
        String authorPart = clean(fields.length > 1 ? fields[1] : "");
        String workPart = clean(fields.length > 2 ? fields[2] : "");
        String categoryPart = fields.length > 3 && !fields[3].trim().isEmpty()
                ? fields[3].trim() : defaultCategory;
        String imagePart = fields.length > 4 ? clean(fields[4]) : "";
        return new Quote(textPart, authorPart, workPart, kind, categoryPart, imagePart);
    }

    /**
     * 老版本在作者/出处为 null 时直接字符串拼接，把字面量 "null" 写进了存储。
     * 这里统一当成空值，免得小组件上出现 “— null · 《null》”。
     */
    private static String clean(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    /** 序列化成存储行；空字段留空，例如 {@code 人生如逆旅|||感悟} 或 {@code 句子|作者|出处|分类|abc.gif}。 */
    public String toLine() {
        String base = text + "|" + author + "|" + work + "|" + category;
        return imagePath.isEmpty() ? base : base + "|" + imagePath;
    }

    /** 展示用的出处部分：{@code — 作者 · 《作品》}；两者都没有时返回空串而不是 "— 《》"。 */
    public String source() {
        String a = author.trim();
        String w = work.trim();
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append(a);
        if (!w.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("《").append(w).append("》");
        }
        return sb.length() == 0 ? "" : "— " + sb;
    }

    public boolean hasSource() {
        return !author.trim().isEmpty() || !work.trim().isEmpty();
    }

    public boolean hasImage() {
        return !imagePath.trim().isEmpty();
    }
}
