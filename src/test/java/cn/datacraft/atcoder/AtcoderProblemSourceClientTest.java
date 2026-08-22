package cn.datacraft.atcoder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtcoderProblemSourceClientTest {
    @Test
    void recognizesSignedOutContestPage() {
        assertThat(AtcoderProblemSourceClient.isSignedOutPage(
                "<a href=\"/login?continue=%2Fcontests%2Fabc472\">Sign In</a>"
        )).isTrue();
        assertThat(AtcoderProblemSourceClient.isSignedOutPage("<p>signed in</p>" )).isFalse();
    }

    @Test
    void recognizesContestRegistrationLinkForUnregisteredAccount() {
        assertThat(AtcoderProblemSourceClient.hasRegistrationLink(
                "<a href=\"/contests/abc472/register\">Register</a>", "abc472"
        )).isTrue();
        assertThat(AtcoderProblemSourceClient.hasRegistrationLink(
                "<a href=\"/contests/abc471/register\">Register</a>", "abc472"
        )).isFalse();
    }
}
