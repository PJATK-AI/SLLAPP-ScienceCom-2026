package pl.pjatk.ai.sllapp.web.websocket;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("recognition-test")
class RecognitionTestPage {
    @GetMapping(value = {"/recognition-test", "/recognition-test/"}, produces = MediaType.TEXT_HTML_VALUE)
    Resource page() {
        return new ClassPathResource("recognition-test/index.html");
    }
}
