package chabaklog.datasource

import chabaklog.datasource.detection.ClassificationConfig
import chabaklog.datasource.detection.SessionClassifier
import chabaklog.datasource.detection.SessionVerdict
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionClassifierTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val c = SessionClassifier(ClassificationConfig(), zone)
    private fun at(d: Int, h: Int, m: Int = 0) =
        LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun `밤 11시부터 아침 7시까지는 차박`() {
        assertEquals(SessionVerdict.CAMPING, c.classify(at(3, 23), at(4, 7), userDeclared = false))
    }

    @Test fun `낮 충전 대기 40분은 휴식`() {
        assertEquals(SessionVerdict.REST, c.classify(at(3, 14), at(3, 14, 40), userDeclared = false))
    }

    @Test fun `휴게소 낮잠 1시간은 휴식`() {
        assertEquals(SessionVerdict.REST, c.classify(at(3, 13), at(3, 14), userDeclared = false))
    }

    @Test fun `낮에 4시간 쉬면 길지만 밤이 아니라 물어본다`() {
        assertEquals(SessionVerdict.ASK, c.classify(at(3, 14), at(3, 18), userDeclared = false))
    }

    @Test fun `밤에 1시간만 머물면 짧아서 물어본다`() {
        assertEquals(SessionVerdict.ASK, c.classify(at(3, 23), at(4, 0), userDeclared = false))
    }

    @Test fun `새벽 2시부터 7시까지도 밤에 걸쳐 차박`() {
        assertEquals(SessionVerdict.CAMPING, c.classify(at(4, 2), at(4, 7), userDeclared = false))
    }

    @Test fun `밤 경계 직전에 끝나면 밤이 아니다`() {
        assertEquals(false, c.overlapsNight(at(3, 18), at(3, 22)))
        assertEquals(true, c.overlapsNight(at(3, 18), at(3, 22, 1)))
        assertEquals(false, c.overlapsNight(at(4, 6), at(4, 9)))
    }

    @Test fun `차박 모드로 직접 기록하면 짧아도 차박`() {
        assertEquals(SessionVerdict.CAMPING, c.classify(at(3, 14), at(3, 14, 20), userDeclared = true))
    }

    @Test fun `충전하며 보낸 밤은 차박 대신 물어본다`() {
        assertEquals(SessionVerdict.ASK, c.classify(at(3, 23), at(4, 7), userDeclared = false, charging = true))
    }

    @Test fun `충전 대기는 밤에 걸쳐도 휴식`() {
        assertEquals(SessionVerdict.REST, c.classify(at(3, 23), at(4, 0), userDeclared = false, charging = true))
    }

    @Test fun `차박 모드면 충전 중이어도 차박`() {
        assertEquals(SessionVerdict.CAMPING, c.classify(at(3, 23), at(4, 7), userDeclared = true, charging = true))
    }

    @Test fun `시연 모드에서 충전이 감지되면 물어본다`() {
        val demo = SessionClassifier(ClassificationConfig(demo = true), zone)
        assertEquals(SessionVerdict.ASK, demo.classify(at(3, 14), at(3, 14, 4), userDeclared = false, charging = true))
    }

    @Test fun `캠핑장에서 밤에 짧게 머문 것은 차박으로 올린다`() {
        assertEquals(SessionVerdict.CAMPING, c.classify(at(3, 23), at(4, 1), userDeclared = false, atKnownSpot = true))
    }

    @Test fun `캠핑장이어도 낮의 짧은 휴식은 휴식`() {
        assertEquals(SessionVerdict.REST, c.classify(at(3, 14), at(3, 15), userDeclared = false, atKnownSpot = true))
    }

    @Test fun `캠핑장에서 충전하며 보낸 밤은 물어본다`() {
        assertEquals(SessionVerdict.ASK, c.classify(at(3, 23), at(4, 7), userDeclared = false, charging = true, atKnownSpot = true))
    }

    @Test fun `시연 모드에서는 몇 분짜리도 차박`() {
        val demo = SessionClassifier(ClassificationConfig(demo = true), zone)
        assertEquals(SessionVerdict.CAMPING, demo.classify(at(3, 14), at(3, 14, 4), userDeclared = false))
    }
}
