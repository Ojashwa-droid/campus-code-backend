package com.campuscode.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record IngestRepositoryRequest(@NotBlank String repositoryUrl,
                                     @Email @jakarta.validation.constraints.Size(max = 320) String notificationEmail) {
}
