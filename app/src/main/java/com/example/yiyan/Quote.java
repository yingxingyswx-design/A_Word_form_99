package com.example.yiyan;
public class Quote {
    public final String text, author, work, kind, category;
    public Quote(String text,String author,String work,String kind){this(text,author,work,kind,"哲学");}
    public Quote(String text,String author,String work,String kind,String category){this.text=text;this.author=author;this.work=work;this.kind=kind;this.category=category;}
}
