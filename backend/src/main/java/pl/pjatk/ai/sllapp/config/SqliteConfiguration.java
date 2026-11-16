package pl.pjatk.ai.sllapp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;

import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SqliteConfiguration {

    @Bean
    DataSource dataSource(@Value("${sllapp.storage.directory}") Path directory) throws IOException {
        Path dataDirectory = Files.createDirectories(directory.toAbsolutePath().normalize());
        Path databaseFile = dataDirectory.resolve("sllapp.db");

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile.toUri());
        return dataSource;
    }
}
