package com.syeyoung.assistant

import com.syeyoung.assistant.source.contests.ContestSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class ContestSourceTest : ApiTestBase() {
    @Autowired lateinit var contests: ContestSource

    @Test
    fun `AtCoder and Codeforces contests of the chosen kinds become timed events`() {
        val atcoder = """
            <div id="contest-table-upcoming"><table><thead><tr><th>Start Time</th><th>Contest Name</th><th>Duration</th><th>Rated Range</th></tr></thead><tbody>
            <tr>
              <td class="text-center"><a href='x'><time class='fixtime fixtime-full'>2026-10-24 21:00:00+0900</time></a></td>
              <td ><span title="Algorithm">Ⓐ</span><span class="user-blue">◉</span> <a href="/contests/abc481">SMBC Programming Contest #2 (AtCoder Beginner Contest 481)</a></td>
              <td class="text-center">01:40</td>
              <td class="text-center"> - 1999</td>
            </tr>
            <tr>
              <td class="text-center"><a href='x'><time class='fixtime fixtime-full'>2026-10-30 19:00:00+0900</time></a></td>
              <td ><span title="Heuristic">Ⓗ</span> <a href="/contests/ahc074">HACK TO THE FUTURE 2027 (AtCoder Heuristic Contest 074)</a></td>
              <td class="text-center">240:00</td>
              <td class="text-center">All</td>
            </tr>
            </tbody></table></div>
            <div id="contest-table-recent"><table><tr><td><time>2026-10-17 21:00:00+0900</time></td><td><a href="/contests/abc480">ABC 480</a></td><td class="text-center">01:40</td></tr></table></div>
        """
        val abc = contests.atcoder(atcoder, listOf("abc", "arc", "agc")).single()
        assertThat(abc.ref).isEqualTo("atcoder:abc481")
        assertThat(abc.title).isEqualTo("SMBC Programming Contest #2 (AtCoder Beginner Contest 481)")
        assertThat(abc.atStart).isEqualTo(Instant.parse("2026-10-24T12:00:00Z"))
        assertThat(abc.atEnd).isEqualTo(Instant.parse("2026-10-24T13:40:00Z"))
        assertThat(abc.calendar?.name).isEqualTo("Contests")

        val codeforces = """
            {"status":"OK","result":[
              {"id":2278,"name":"Codeforces Round (Div. 3)","phase":"BEFORE","durationSeconds":8100,"startTimeSeconds":1793457300},
              {"id":2279,"name":"Kotlin Heroes: Practice","phase":"BEFORE","durationSeconds":7200,"startTimeSeconds":1793457300},
              {"id":2270,"name":"Educational Codeforces Round 190","phase":"FINISHED","durationSeconds":7200,"startTimeSeconds":1790000000}
            ]}
        """
        val round = contests.codeforces(codeforces, listOf("Div. 1", "Div. 2", "Div. 3", "Div. 4", "Educational")).single()
        assertThat(round.ref).isEqualTo("codeforces:2278")
        assertThat(round.url).isEqualTo("https://codeforces.com/contest/2278")
        assertThat(round.atEnd).isEqualTo(Instant.ofEpochSecond(1793457300 + 8100))
    }
}
