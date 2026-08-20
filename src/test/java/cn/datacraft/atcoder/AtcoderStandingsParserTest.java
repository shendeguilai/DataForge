package cn.datacraft.atcoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtcoderStandingsParserTest {
    private final AtcoderStandingsParser parser = new AtcoderStandingsParser(new ObjectMapper());

    @Test
    void parsesOfficialScoresFailuresPendingAndFrozenResults() {
        AtcoderStandings.Snapshot snapshot = parser.parse("""
                {
                  "TaskInfo": [
                    {"Assignment":"A","TaskName":"Warm Up","TaskScreenName":"abc430_a","Score":10000},
                    {"Assignment":"B","TaskName":"Graph","TaskScreenName":"abc430_b","Score":20000}
                  ],
                  "StandingsData": [
                    {
                      "Rank":12,"UserScreenName":"Alice",
                      "TotalResult":{"Score":30000,"Elapsed":3723000000000,"Penalty":1},
                      "TaskResults":{
                        "abc430_a":{"Score":10000,"Elapsed":63000000000,"Failure":1,"Penalty":1,"Count":2,"Pending":false,"Frozen":false,"Status":1},
                        "abc430_b":{"Score":0,"Elapsed":0,"Failure":0,"Penalty":0,"Count":1,"Pending":true,"Frozen":false,"Status":0}
                      }
                    },
                    {
                      "Rank":99,"UserScreenName":"BOB",
                      "TotalResult":{"Score":0,"Elapsed":0,"Penalty":2},
                      "TaskResults":{
                        "abc430_a":{"Score":0,"Elapsed":0,"Failure":2,"Penalty":2,"Count":2,"Pending":false,"Frozen":true,"Status":0}
                      }
                    }
                  ]
                }
                """);

        assertThat(snapshot.tasks()).extracting(AtcoderStandings.Task::label).containsExactly("A", "B");
        assertThat(snapshot.tasks().get(0).maxScore()).isEqualByComparingTo("100");
        assertThat(snapshot.tasks().get(1).maxScore()).isEqualByComparingTo("200");
        AtcoderStandings.Entry alice = snapshot.entriesByUsernameKey().get("alice");
        assertThat(alice.officialRank()).isEqualTo(12);
        assertThat(alice.totalScore()).isEqualByComparingTo("300");
        assertThat(alice.taskResults().get("abc430_a").score()).isEqualByComparingTo("100");
        assertThat(alice.elapsedNanos()).isEqualTo(3_723_000_000_000L);
        assertThat(alice.taskResults().get("abc430_a").failure()).isEqualTo(1);
        assertThat(alice.taskResults().get("abc430_b").pending()).isTrue();
        assertThat(snapshot.entriesByUsernameKey().get("bob").taskResults().get("abc430_a").frozen()).isTrue();
    }
}
