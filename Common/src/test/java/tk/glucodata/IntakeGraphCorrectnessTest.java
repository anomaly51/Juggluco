package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Regression coverage for the backend-owned meal/insulin graph projection. */
@RunWith(RobolectricTestRunner.class)
public class IntakeGraphCorrectnessTest {
    @Test
    public void explicitZeroCarbsSurvivesJsonAndStillMeansMeal()
            throws Exception {
        JSONObject source=new JSONObject()
                .put("id","zero-carb")
                .put("occurred_at_ms",1234L)
                .put("meal_text","")
                .put("carbs_g",0.0);

        IntakeEvent event=IntakeEvent.fromJson(source);
        assertTrue(event.hasCarbs());
        assertTrue(event.hasMeal());
        assertEquals(0.0f,event.carbsGrams,0.0f);

        JSONObject cached=event.toJson();
        assertTrue(cached.has("carbs_g"));
        IntakeEvent restored=IntakeEvent.fromJson(cached);
        assertTrue(restored.hasCarbs());
        assertTrue(restored.hasMeal());
        assertEquals(0.0f,restored.carbsGrams,0.0f);
    }

    @Test
    public void absentCarbsStaysAbsentAcrossCacheRoundTrip()
            throws Exception {
        JSONObject source=new JSONObject()
                .put("id","insulin-only")
                .put("occurred_at_ms",1234L)
                .put("insulin_units",2.0);

        IntakeEvent event=IntakeEvent.fromJson(source);
        assertFalse(event.hasCarbs());
        assertFalse(event.hasMeal());
        assertFalse(event.toJson().has("carbs_g"));
    }

    @Test
    public void collidingStringHashesReceiveDifferentSnapshotKeys()
            throws Exception {
        assertEquals("Test precondition: these Java strings collide",
                "FB".hashCode(),"Ea".hashCode());
        IntakeEvent first=event("FB",1000L);
        IntakeEvent second=event("Ea",2000L);
        IntakeRepository repository=IntakeRepository.get(
                RuntimeEnvironment.getApplication());

        int[] keys=repository.assignRenderKeys(Arrays.asList(first,second));
        assertNotEquals(0,keys[0]);
        assertNotEquals(0,keys[1]);
        assertNotEquals(keys[0],keys[1]);
        assertSame(first,repository.findByRenderKey(keys[0]));
        assertSame(second,repository.findByRenderKey(keys[1]));

        IntakeEvent refreshedSecond=event("Ea",3000L);
        IntakeEvent refreshedFirst=event("FB",4000L);
        int[] refreshedKeys=repository.assignRenderKeys(
                Arrays.asList(refreshedSecond,refreshedFirst));
        assertEquals(keys[1],refreshedKeys[0]);
        assertEquals(keys[0],refreshedKeys[1]);
        assertSame(refreshedSecond,
                repository.findByRenderKey(refreshedKeys[0]));
        assertSame(refreshedFirst,
                repository.findByRenderKey(refreshedKeys[1]));
    }

    @Test
    public void nativeHitsFollowTheChipGroupInsteadOfItsConnector()
            throws Exception {
        String curve=source(Paths.get("src","main","cpp","curve","curve.cpp"));
        int start=curve.indexOf("hits.push_back({{event.key}");
        int end=curve.indexOf("});",start);
        assertTrue(start>=0&&end>start);
        String hit=curve.substring(start,end);
        assertTrue(hit.contains("groupLeft+groupWidth"));
        assertTrue(hit.contains("groupTop+groupHeight"));
        assertFalse("An upper-lane hit must not extend through the lower chip",
                hit.contains("laneBottom"));
    }

    @Test
    public void carbPresenceHasAnExplicitJavaToNativeFlag()
            throws Exception {
        String dashboard=source(Paths.get("src","main","java","tk",
                "glucodata","DashboardChrome.java"));
        String nativeHeader=source(Paths.get("src","main","cpp","curve",
                "intakeevents.hpp"));
        String renderer=source(Paths.get("src","main","cpp","curve","curve.cpp"));

        assertTrue(dashboard.contains(
                "event.hasCarbs()?INTAKE_FLAG_CARBS_PRESENT:0"));
        assertTrue(nativeHeader.contains("IntakeTimelineCarbsPresent"));
        assertTrue(renderer.contains("event.flags&IntakeTimelineCarbsPresent"));
    }

    @Test
    public void timelineMarkersUseTheRenderedGlucoseYAtEventTime()
            throws Exception {
        String renderer=source(Paths.get("src","main","cpp","curve","curve.cpp"));
        String helper=source(Paths.get("src","main","cpp","curve",
                "intakemarkers.hpp"));

        assertTrue(renderer.contains("intakemarkers::anchor_y("));
        assertTrue(renderer.contains("point->g*10"));
        assertTrue(renderer.contains("point->getsputnik()"));
        assertTrue(renderer.contains("marker.anchorX,marker.anchorY"));
        assertFalse("The old bottom marker lane must stay removed",
                renderer.contains("const float laneBottom="));
        assertTrue(helper.contains("maxInterpolationGapSeconds"));
        assertTrue(helper.contains("static_assert(interpolate_y"));
    }

    @Test
    public void denseMarkersClusterWithoutOverlappingDetailHits()
            throws Exception {
        String renderer=source(Paths.get("src","main","cpp","curve","curve.cpp"));
        String helper=source(Paths.get("src","main","cpp","curve",
                "intakemarkers.hpp"));

        assertTrue(renderer.contains("intakemarkers::joins_cluster("));
        assertTrue(renderer.contains("std::vector<MarkerCluster> clusters"));
        assertTrue(renderer.contains("clusterMaximumSpan"));
        assertTrue(renderer.contains("visualCollisionDistance"));
        assertTrue(renderer.contains("keys.push_back(marker.event.key)"));
        assertTrue(renderer.contains("hits.push_back({keys"));
        assertTrue(helper.contains("static_assert(joins_cluster"));
    }

    @Test
    public void novoRapidAndTresibaMapToDifferentNativeFlagBits()
            throws Exception {
        IntakeEvent novoRapid=IntakeEvent.fromJson(new JSONObject()
                .put("id","rapid")
                .put("occurred_at_ms",1000L)
                .put("insulin_units",3.0)
                .put("insulin_name","NovoRapid")
                .put("insulin_type","rapid"));
        IntakeEvent tresiba=IntakeEvent.fromJson(new JSONObject()
                .put("id","long")
                .put("occurred_at_ms",2000L)
                .put("insulin_units",8.0)
                .put("insulin_name","Tresiba")
                .put("insulin_type","long"));

        assertEquals(1<<2,DashboardChrome.insulinGraphFlag(novoRapid));
        assertEquals(1<<3,DashboardChrome.insulinGraphFlag(tresiba));

        String dashboard=source(Paths.get("src","main","java","tk",
                "glucodata","DashboardChrome.java"));
        String nativeHeader=source(Paths.get("src","main","cpp","curve",
                "intakeevents.hpp"));
        String bridge=source(Paths.get("src","main","cpp","curve",
                "javacurve.cpp"));
        assertTrue(dashboard.contains("|insulinGraphFlag(event)"));
        assertTrue(nativeHeader.contains("IntakeTimelineRapidInsulin = 1U << 2U"));
        assertTrue(nativeHeader.contains("IntakeTimelineLongInsulin = 1U << 3U"));
        assertTrue(bridge.contains("static_cast<uint32_t>(flagValues[index])"));
    }

    @Test
    public void mixedInsulinClusterKeepsTypeColoursAndEveryEventKey()
            throws Exception {
        String renderer=source(Paths.get("src","main","cpp","curve","curve.cpp"));

        assertTrue(renderer.contains("hasClusterRapid"));
        assertTrue(renderer.contains("hasClusterLong"));
        assertTrue(renderer.contains("drawKindDot(hasClusterRapid,rapidBorder)"));
        assertTrue(renderer.contains("drawKindDot(hasClusterLong,longBorder)"));
        assertTrue(renderer.contains("keys.push_back(marker.event.key)"));
        assertTrue(renderer.contains("hits.push_back({keys"));
    }

    @Test
    public void insulinKindsHaveDistinctColourAndAnchorShape()
            throws Exception {
        String renderer=source(Paths.get("src","main","cpp","curve","curve.cpp"));

        assertTrue(renderer.contains("rapidBorder=hexcolor(0x55C8F2)"));
        assertTrue(renderer.contains("longBorder=hexcolor(0xB69AF5)"));
        assertTrue(renderer.contains("drawCircleSymbol(insulinX"));
        assertTrue(renderer.contains("drawDiamondSymbol(insulinX"));
        assertTrue(renderer.contains("drawCircleSymbol(marker.anchorX-symbolOffset"));
        assertTrue("Meal symbols must remain rendered",
                renderer.contains("mealBorder,marker.glucoseAnchored"));
    }

    private static IntakeEvent event(String id,long time) throws Exception {
        return IntakeEvent.fromJson(new JSONObject()
                .put("id",id)
                .put("occurred_at_ms",time)
                .put("insulin_units",1.0));
    }

    private static String source(Path relative) throws Exception {
        Path path=Files.exists(relative)?relative:Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
