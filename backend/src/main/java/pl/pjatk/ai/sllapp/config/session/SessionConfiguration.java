package pl.pjatk.ai.sllapp.config.session;

import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.pjatk.ai.sllapp.game.GameRules;
import pl.pjatk.ai.sllapp.game.RandomWordPicker;
import pl.pjatk.ai.sllapp.game.WordCatalog;
import pl.pjatk.ai.sllapp.game.WordPicker;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SessionProperties.class, RecognitionProperties.class, ScoringProperties.class})
class SessionConfiguration {
    @Bean
    GameRules sessionGameRules(ScoringProperties scoring) {
        return new GameRules(GameRules.DEFAULT_MAX_WORD_LENGTH, scoring.letterPoints(), scoring.wordBonus(),
                scoring.multiplierStep());
    }

    @Bean
    @ConditionalOnMissingBean(name = "sessionWordPickerFactory")
    Supplier<WordPicker> sessionWordPickerFactory() throws IOException {
        var catalog = WordCatalog.loadBundled();
        return () -> new RandomWordPicker(catalog);
    }
}
