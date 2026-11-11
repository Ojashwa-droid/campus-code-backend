package com.campuscode.api;

import jakarta.validation.constraints.NotBlank;

public record SearchRepositoryRequest(@NotBlank String repositoryUrl, @NotBlank String query) {
}
