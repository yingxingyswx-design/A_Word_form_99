package com.example.yiyan;

/**
 * 一条一言。作者与出处可以为空（用户只写句子也是合法条目），只有 {@link #text} 是必填。
 * 存储格式统一为 {@code 句子|作者|出处|分类}，见 {@link #toLine()} / {@link #parse(String, String, String)}。
 */
public class Quote {

    public final String text, author, work, kind, category;

    public Quote(String text, String author, String work, String kind) {
        this(text, author, work, kind, "哲学");
    }

    public Quote(String text, String author, String work, String kind, String category) {
        this.text = text == null ? "" : text;
        this.author = author == null ? "" : author;
        this.work = work == null ? "" : work;
        this.kind = kind == null ? "" : kind;
        this.category = category == null || category.trim().isEmpty() ? "用户" : category;
    }

    /**
     * 解析一行存储数据。作者、出处、分类都可缺省，只有句子必须非空。
     * 兼容 3 段（句子|作者|出处）与 4 段两种历史格式。
     *
     * @return 解析失败（句子为空）时返回 null
     */
    public static Quote parse(String line, String kind, String defaultCategory) {
        if (line == null) return null;
        String[] fields = line.split("\\|", -1);
        String textPart = fields.length > 0 ? fields[0].trim() : "";
        if (textPart.isEmpty()) return null;
        String authorPart = fields.length > 1 ? fields[1].trim() : "";
        String workPart = fields.length > 2 ? fields[2].trim() : "";
        String categoryPart = fields.length > 3 && !fields[3].trim().isEmpty()
                ? fields[3].trim() : defaultCategory;
        return new Quote(textPart, authorPart, workPart, kind, categoryPart);
    }

    /** 序列化成存储行，字段缺失时留空，例如 {@code 人生如逆旅|||感悟}。 */
    public String toLine() {
        return text + "|" + author + "|" + work + "|" + category;
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
}
