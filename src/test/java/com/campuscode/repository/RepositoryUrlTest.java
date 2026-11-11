package com.campuscode.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryUrlTest {
    @Test
    void canonicalizesTrailingSlashAndGitSuffixToSameDataset() {
        RepositoryUrl plain = RepositoryUrl.parse("https://github.com/example/project");
        RepositoryUrl suffixed = RepositoryUrl.parse("https://github.com/example/project.git/");

        assertThat(plain.canonicalUrl()).isEqualTo("https://github.com/example/project");
        assertThat(suffixed.canonicalUrl()).isEqualTo(plain.canonicalUrl());
        assertThat(suffixed.datasetName()).isEqualTo(plain.datasetName());
    }

    @Test
    void rejectsNonGithubHostsAndRepositorySubpaths() {
        assertThatThrownBy(() -> RepositoryUrl.parse("https://github.example.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryUrl.parse("https://github.com/a/b/tree/main"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCredentialsAndNonHttpsUrls() {
        assertThatThrownBy(() -> RepositoryUrl.parse("http://github.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryUrl.parse("https://user@github.com/a/b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
