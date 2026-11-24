package pl.pjatk.ai.sllapp.config.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.sqlite.SQLiteDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class SqliteConfiguration {

    @Bean
    DataSource dataSource(StorageProperties properties) throws IOException, SQLException {
        Path dataDirectory = Files.createDirectories(properties.directory().toAbsolutePath().normalize());
        Path databaseFile = dataDirectory.resolve("sllapp.db");

        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile.toUri());
        SqliteSchema.initialize(dataSource);
        return dataSource;
    }
}
