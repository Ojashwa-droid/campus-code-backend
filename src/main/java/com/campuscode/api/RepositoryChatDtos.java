package com.campuscode.api;

public final class RepositoryChatDtos {
    private RepositoryChatDtos() { }

    public record FinalAnswer(String answer) { }
    public record StreamError(String error) { }
    public record Delta(String text) { }
}
