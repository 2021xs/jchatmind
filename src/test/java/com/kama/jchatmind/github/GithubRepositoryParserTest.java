package com.kama.jchatmind.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubRepositoryParserTest {
    private final GithubRepositoryParser parser = new GithubRepositoryParser();
    private final GithubRepositoryValidator validator = new GithubRepositoryValidator();

    @Test
    void parsesRepositoryWithoutGitSuffix() {
        GithubRepository repository = parseAndValidate("https://github.com/user/repo");

        assertThat(repository.host()).isEqualTo("github.com");
        assertThat(repository.owner()).isEqualTo("user");
        assertThat(repository.repository()).isEqualTo("repo");
    }

    @Test
    void stripsGitSuffix() {
        GithubRepository repository = parseAndValidate("https://github.com/spring-projects/spring-boot.git");

        assertThat(repository.repository()).isEqualTo("spring-boot");
    }

    @Test
    void allowsOneTrailingSlash() {
        GithubRepository repository = parseAndValidate("https://github.com/user/repo/");

        assertThat(repository.repository()).isEqualTo("repo");
    }

    @Test
    void rejectsNonGithubHost() {
        assertThatThrownBy(() -> validator.validate(parser.parse("https://gitlab.com/user/repo")))
                .isInstanceOf(GithubRepositoryUrlException.class)
                .hasMessageContaining("host is not allowed");
    }

    @Test
    void rejectsMaliciousGithubLikeHost() {
        assertThatThrownBy(() -> validator.validate(parser.parse("https://github.com.evil.com/user/repo")))
                .isInstanceOf(GithubRepositoryUrlException.class)
                .hasMessageContaining("host is not allowed");
    }

    @Test
    void rejectsUnsupportedSchemes() {
        assertThatThrownBy(() -> parser.parse("http://github.com/user/repo"))
                .isInstanceOf(GithubRepositoryUrlException.class)
                .hasMessageContaining("scheme must be https");
        assertThatThrownBy(() -> parser.parse("ssh://github.com/user/repo.git"))
                .isInstanceOf(GithubRepositoryUrlException.class);
    }

    @Test
    void rejectsUserinfoAndQuery() {
        assertThatThrownBy(() -> parser.parse("https://user:password@github.com/user/repo"))
                .isInstanceOf(GithubRepositoryUrlException.class)
                .hasMessageContaining("userinfo");
        assertThatThrownBy(() -> parser.parse("https://github.com/user/repo?token=xxx"))
                .isInstanceOf(GithubRepositoryUrlException.class)
                .hasMessageContaining("query");
    }

    @Test
    void rejectsInvalidPaths() {
        assertThatThrownBy(() -> parser.parse("https://github.com/"))
                .isInstanceOf(GithubRepositoryUrlException.class);
        assertThatThrownBy(() -> parser.parse("https://github.com/user"))
                .isInstanceOf(GithubRepositoryUrlException.class);
        assertThatThrownBy(() -> parser.parse("https://github.com/user/repo/extra"))
                .isInstanceOf(GithubRepositoryUrlException.class);
    }

    @Test
    void rejectsUnsafeRepositoryNameCharacters() {
        assertThatThrownBy(() -> validator.validate(parser.parse("https://github.com/user/repo%20name")))
                .isInstanceOf(GithubRepositoryUrlException.class)
                .hasMessageContaining("unsafe path segment");
    }

    @Test
    void rejectsBlankAndNonUrlInput() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(GithubRepositoryUrlException.class)
                .hasMessageContaining("URL is blank");
        assertThatThrownBy(() -> parser.parse(" "))
                .isInstanceOf(GithubRepositoryUrlException.class);
        assertThatThrownBy(() -> parser.parse("github.com/user/repo"))
                .isInstanceOf(GithubRepositoryUrlException.class);
    }

    @Test
    void parserAndValidatorRemainSeparate() {
        GithubRepository parsed = parser.parse("https://gitlab.com/user/repo");

        assertThat(parsed.owner()).isEqualTo("user");
        assertThat(parsed.repository()).isEqualTo("repo");
        assertThatThrownBy(() -> validator.validate(parsed))
                .isInstanceOf(GithubRepositoryUrlException.class);
    }

    private GithubRepository parseAndValidate(String rawUrl) {
        GithubRepository repository = parser.parse(rawUrl);
        validator.validate(repository);
        return repository;
    }
}
