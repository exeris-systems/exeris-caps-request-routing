package eu.exeris.caps.routing;

import eu.exeris.caps.routing.api.PathRewriteRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PathRewriteRule Unit Tests")
class PathRewriteRuleTest {

    @Test
    @DisplayName("stripPrefix removes leading prefix correctly")
    void testStripPrefix() {
        PathRewriteRule rule = PathRewriteRule.stripPrefix("/service-prefix");
        assertThat(rule.rewrite("/service-prefix/test")).isEqualTo("/test");
        assertThat(rule.rewrite("/service-prefix/foo/bar")).isEqualTo("/foo/bar");
        assertThat(rule.rewrite("/service-prefix")).isEqualTo("/");
        assertThat(rule.rewrite("/other-service/test")).isEqualTo("/other-service/test");
    }

    @Test
    @DisplayName("prefixRewrite replaces leading prefix correctly")
    void testPrefixRewrite() {
        PathRewriteRule rule = PathRewriteRule.prefixRewrite("/api/v1", "/internal/v2");
        assertThat(rule.rewrite("/api/v1/users")).isEqualTo("/internal/v2/users");
        assertThat(rule.rewrite("/api/v1")).isEqualTo("/internal/v2");
    }

    @Test
    @DisplayName("exactRewrite replaces path completely")
    void testExactRewrite() {
        PathRewriteRule rule = PathRewriteRule.exactRewrite("/target/fixed");
        assertThat(rule.rewrite("/any/old/path")).isEqualTo("/target/fixed");
    }

    @Test
    @DisplayName("none leaves path unchanged")
    void testNone() {
        PathRewriteRule rule = PathRewriteRule.none();
        assertThat(rule.rewrite("/any/path")).isEqualTo("/any/path");
    }
}
