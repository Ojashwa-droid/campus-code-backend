package com.campuscode.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RepositoryChatRequest(
        @NotBlank @Size(max = 2000) String query,
        @Size(max = 12) List<@Valid Turn> history) {
    public enum Role { USER, ASSISTANT }

    public record Turn(@NotNull Role role, @NotBlank @Size(max = 1500) String content) { }
}
