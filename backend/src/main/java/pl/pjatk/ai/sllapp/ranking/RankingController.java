package pl.pjatk.ai.sllapp.ranking;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RankingController {
    private final RankingService ranking;

    public RankingController(RankingService ranking) {
        this.ranking = ranking;
    }

    @GetMapping("/api/ranking")
    public List<RankingEntryResponse> getRanking(@RequestParam(name = "limit", required = false) String rawLimit) {
        int limit = 10;
        if (rawLimit != null) {
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (NumberFormatException exception) {
                throw new InvalidLimitException();
            }
            if (limit < 1 || limit > 100) {
                throw new InvalidLimitException();
            }
        }
        return ranking.findRanking(limit);
    }

    @ExceptionHandler(InvalidLimitException.class)
    public ProblemDetail invalidLimit() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "limit must be an integer between 1 and 100.");
    }

    private static class InvalidLimitException extends RuntimeException {
    }
}
