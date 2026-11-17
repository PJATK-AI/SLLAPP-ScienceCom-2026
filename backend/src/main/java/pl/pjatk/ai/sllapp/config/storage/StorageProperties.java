package pl.pjatk.ai.sllapp.config.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sllapp.storage")
record StorageProperties(Path directory) {
}
