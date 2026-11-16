package pl.pjatk.ai.sllapp.ranking;

import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import pl.pjatk.ai.sllapp.game.result.GameResultRepository;

@Service
public class RankingService {
    private final GameResultRepository results;

    public RankingService(GameResultRepository results) {
        this.results = results;
    }

    public List<RankingEntryResponse> findRanking(int limit) {
        var ordered = results.findTopResults(limit);
        return IntStream.range(0, ordered.size())
                .mapToObj(index -> new RankingEntryResponse(index + 1,
                        ordered.get(index).nickname(), ordered.get(index).score()))
                .toList();
    }
}
