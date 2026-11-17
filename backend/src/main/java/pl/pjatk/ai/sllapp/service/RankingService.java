package pl.pjatk.ai.sllapp.service;

import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import pl.pjatk.ai.sllapp.model.RankingEntry;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;

@Service
public class RankingService {
    private final GameResultRepository results;

    public RankingService(GameResultRepository results) {
        this.results = results;
    }

    public List<RankingEntry> findRanking(int limit) {
        var ordered = results.findTopResults(limit);
        return IntStream.range(0, ordered.size())
                .mapToObj(index -> new RankingEntry(index + 1,
                        ordered.get(index).nickname(), ordered.get(index).score()))
                .toList();
    }
}
