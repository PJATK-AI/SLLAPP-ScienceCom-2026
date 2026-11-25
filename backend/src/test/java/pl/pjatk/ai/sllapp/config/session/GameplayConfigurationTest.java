package pl.pjatk.ai.sllapp.config.session;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.DefaultResourceLoader;
import pl.pjatk.ai.sllapp.config.moderation.ModerationProperties;
import pl.pjatk.ai.sllapp.game.WordPicker;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.model.SelectedHand;
import pl.pjatk.ai.sllapp.moderation.BlockedWordList;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.support.MutableClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GameplayConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(SessionConfiguration.class);

    @Test
    void usesThePackagedYamlDefaultsInRegularSessions() {
        var clock = new MutableClock();
        withSessions(clock).run(app -> {
            assertThat(app.getBean(SessionProperties.class)).isEqualTo(new SessionProperties(
                    Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofMinutes(5)));
            assertThat(app.getBean(RecognitionProperties.class)).isEqualTo(new RecognitionProperties(
                    0.80, 3, Duration.ofMillis(350), 2, Duration.ofMillis(250)));

            var sessions = app.getBean(SessionService.class);
            var session = sessions.create("Ola", SelectedHand.RIGHT);
            synchronized (session) {
                assertThat(sessions.start(session, clock.instant())).isTrue();
                assertThat(session.state(clock.instant()).remainingMs()).isEqualTo(60_000);
                assertWordAwards(session, clock, 50, 180);
            }
        });
    }

    @Test
    void appliesExternalYamlOverridesToARegularSession(@TempDir Path directory) throws IOException {
        var config = directory.resolve("application.yml");
        Files.writeString(config, """
                sllapp:
                  session:
                    game-duration: 30s
                  game:
                    scoring:
                      letter-points: 7.5
                      word-bonus: 12.5
                      multiplier-step: 0.25
                    recognition:
                      min-hold-duration: 600ms
                """);
        var clock = new MutableClock();
        withSessions(clock).withPropertyValues("spring.config.additional-location=" + config.toUri()).run(app -> {
            assertThat(app.getBean(RecognitionProperties.class).minHoldDuration()).isEqualTo(Duration.ofMillis(600));
            var sessions = app.getBean(SessionService.class);
            var session = sessions.create("Ola", SelectedHand.RIGHT);
            synchronized (session) {
                assertThat(sessions.start(session, clock.instant())).isTrue();
                assertThat(session.state(clock.instant()).remainingMs()).isEqualTo(30_000);
                assertWordAwards(session, clock, 37, 52);
                clock.advance(Duration.ofMillis(29_999));
                assertThat(session.finishIfDue(clock.instant())).isFalse();
                clock.advance(Duration.ofMillis(1));
                assertThat(session.finishIfDue(clock.instant())).isTrue();
                assertThat(session.result().score()).isEqualTo(89);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "session.game-duration=0ms", "session.game-duration=-1s",
            "session.creation-ttl=0ms", "session.creation-ttl=-1s",
            "session.finished-ttl=0ms", "session.finished-ttl=-1s",
            "game.scoring.letter-points=-1", "game.scoring.word-bonus=-1", "game.scoring.multiplier-step=-1",
            "game.recognition.min-confidence=NaN", "game.recognition.min-confidence=Infinity",
            "game.recognition.min-confidence=-0.01", "game.recognition.min-confidence=1.01",
            "game.recognition.required-predictions=0", "game.recognition.required-predictions=-1",
            "game.recognition.release-no-hand-count=0", "game.recognition.release-no-hand-count=-1",
            "game.recognition.max-sample-gap=0ms", "game.recognition.max-sample-gap=-1ms",
            "game.recognition.min-hold-duration=-1ms"
    })
    void rejectsInvalidConfigurationAtStartupAndNamesTheSetting(String override) {
        var property = "sllapp." + override;
        var key = property.substring(0, property.indexOf('='));
        context.withPropertyValues(property).run(app -> {
            assertThat(app).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(app.getStartupFailure())).hasMessageContaining(key);
        });
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 1})
    void acceptsInclusiveConfidenceBoundsAndZeroScoringAndHold(double confidence) {
        context.withPropertyValues("sllapp.game.recognition.min-confidence=" + confidence,
                "sllapp.game.recognition.min-hold-duration=0ms", "sllapp.game.scoring.letter-points=0",
                "sllapp.game.scoring.word-bonus=0", "sllapp.game.scoring.multiplier-step=0").run(app -> {
            var recognition = app.getBean(RecognitionProperties.class);
            assertThat(recognition.minConfidence()).isEqualTo(confidence);
            assertThat(recognition.minHoldDuration()).isZero();
            assertThat(app.getBean(ScoringProperties.class))
                    .isEqualTo(new ScoringProperties(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        });
    }

    private ApplicationContextRunner withSessions(Clock clock) {
        Supplier<WordPicker> pickers = () -> length -> length == 3 ? "LAS" : "A".repeat(length);
        return context.withUserConfiguration(SessionService.class)
                .withBean("sessionWordPickerFactory", Supplier.class, () -> pickers)
                .withBean(Clock.class, () -> clock)
                .withBean(GameResultRepository.class, () -> mock(GameResultRepository.class))
                .withBean(NicknamePolicy.class, () -> new NicknamePolicy(new BlockedWordList(
                        new ModerationProperties("classpath:moderation/test-blocked-words.txt"),
                        new DefaultResourceLoader())));
    }

    private static void assertWordAwards(GameSession session, Clock clock, int threeLetterAward,
                                         int fourLetterAward) {
        var now = clock.instant();
        assertThat(session.state(now).word()).hasSize(3);
        for (long position = 1; position <= 3; position++) {
            assertThat(session.completePosition(position, now)).isTrue();
        }
        assertThat(session.state(now).score()).isEqualTo(threeLetterAward);
        assertThat(session.state(now).word()).hasSize(4);
        for (long position = 4; position <= 7; position++) {
            assertThat(session.completePosition(position, now)).isTrue();
        }
        assertThat(session.state(now).score()).isEqualTo(threeLetterAward + fourLetterAward);
    }
}
