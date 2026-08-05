package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class GraphGestureStateTest {
    @Test
    public void movementInsideTouchSlopDoesNotChooseAnAxis() {
        GraphGestureState state=new GraphGestureState(10.0f);
        state.beginSingleFinger(100.0f,100.0f);

        assertEquals(GraphGestureState.Axis.UNDECIDED,
                state.updateSingleFinger(109.0f,104.0f));
    }

    @Test
    public void horizontalPanLocksAndVerticalJitterCannotChangeIt() {
        GraphGestureState state=new GraphGestureState(8.0f);
        state.beginSingleFinger(100.0f,100.0f);

        assertEquals(GraphGestureState.Axis.HORIZONTAL_PAN,
                state.updateSingleFinger(122.0f,104.0f));
        assertEquals(GraphGestureState.Axis.HORIZONTAL_PAN,
                state.updateSingleFinger(124.0f,160.0f));
        assertTrue(state.allowsHorizontalFling(2400.0f,300.0f,2000.0f));
    }

    @Test
    public void predominantlyVerticalPhoneGestureIsIgnored() {
        GraphGestureState state=new GraphGestureState(8.0f);
        state.beginSingleFinger(100.0f,100.0f);

        assertEquals(GraphGestureState.Axis.VERTICAL_IGNORED,
                state.updateSingleFinger(103.0f,124.0f));
        assertFalse(state.allowsHorizontalFling(3000.0f,100.0f,2000.0f));
    }

    @Test
    public void initialVerticalJitterDoesNotStealAHorizontalPan() {
        GraphGestureState state=new GraphGestureState(8.0f);
        state.beginSingleFinger(100.0f,100.0f);

        assertEquals(GraphGestureState.Axis.UNDECIDED,
                state.updateSingleFinger(103.0f,109.0f));
        assertEquals(GraphGestureState.Axis.HORIZONTAL_PAN,
                state.updateSingleFinger(122.0f,111.0f));
    }

    @Test
    public void ambiguousDiagonalWaitsForClearIntent() {
        assertEquals(GraphGestureState.Axis.UNDECIDED,
                GraphGestureState.resolveAxis(GraphGestureState.Axis.UNDECIDED,
                        10.0f,9.0f,8.0f));
        assertEquals(GraphGestureState.Axis.HORIZONTAL_PAN,
                GraphGestureState.resolveAxis(GraphGestureState.Axis.UNDECIDED,
                        18.0f,17.0f,8.0f));
    }

    @Test
    public void pinchSuppressesPanUntilANewSingleFingerSequence() {
        GraphGestureState state=new GraphGestureState(8.0f);
        state.beginSingleFinger(0.0f,0.0f);
        assertEquals(GraphGestureState.Axis.HORIZONTAL_PAN,
                state.updateSingleFinger(20.0f,1.0f));

        state.beginPinch();
        assertTrue(state.isPinchSequence());
        assertEquals(GraphGestureState.Axis.UNDECIDED,
                state.updateSingleFinger(40.0f,1.0f));
        assertFalse(state.allowsHorizontalFling(3000.0f,0.0f,2000.0f));

        state.endSequence();
        state.beginSingleFinger(40.0f,1.0f);
        assertFalse(state.isPinchSequence());
        assertEquals(GraphGestureState.Axis.HORIZONTAL_PAN,
                state.updateSingleFinger(60.0f,2.0f));
    }

    @Test
    public void javaTouchContractKeepsHorizontalPanOutOfTheYRange() throws Exception {
        String source=source("GlucoseCurve.java");
        int scrollStart=source.indexOf("public boolean onScroll(MotionEvent e1");
        int scrollEnd=source.indexOf("public void onLongPress",scrollStart);
        String scroll=source.substring(scrollStart,scrollEnd);
        String compactScroll=scroll.replaceAll("\\s+","");
        assertTrue(compactScroll.contains(
                "Natives.translate(distanceX,0.0f,currentRawY,currentRawY)"));
        assertTrue(scroll.contains("else if(isWearable||e1.isFromSource("));
        assertTrue(scroll.contains(
                "Natives.translate(distanceX,distanceY,e1.getRawY(),e2.getRawY())"));
        assertTrue(scroll.contains("axis==GraphGestureState.Axis.HORIZONTAL_PAN"));
        assertTrue(scroll.contains("translated=0;"));
        assertTrue(scroll.contains("Natives.lockGraphYRangeForPan()"));
        assertFalse(scroll.contains("Natives.xscale("));
        assertFalse(scroll.contains("Natives.translate(0.0f,distanceY"));
        assertTrue(source.contains("mScaleDetector.onTouchEvent(event)"));
        assertTrue(source.contains("graphGestureState.isPinchSequence()"));
        assertTrue(source.contains("graphGestureState.allowsHorizontalFling("));
        assertTrue(source.contains("Natives.xscale(scalex, focusx)"));
        assertTrue(source.contains("Natives.graphscrub("));
        assertTrue(source.contains("Natives.tap(x, y)"));
        assertTrue(source.contains("Natives.prevday(1)"));
        assertTrue(source.contains("Natives.nextday(1)"));
        assertTrue(source.contains("Natives.settonow()"));
        assertTrue(source.contains("Natives.flingX(velocityX)"));

        String dashboard=source("DashboardChrome.java");
        assertTrue(dashboard.contains("RANGE_HOURS = {3, 6, 8, 12, 24}"));
        assertTrue(dashboard.contains("Natives.setgraphhours(hours)"));
    }

    @Test
    public void nativePanLockPersistsAndOnlyExplicitActionsResetIt() throws Exception {
        String header=nativeSource("curve","JCurve.hpp");
        assertTrue(header.contains("bool graphPanYRangeLocked=false;"));
        assertTrue(header.contains("void lockGraphYRangeForPan();"));
        assertTrue(header.contains("void unlockGraphYRange();"));

        String appcurve=nativeSource("curve","appcurve.cpp");
        assertTrue(appcurve.contains("void JCurve::lockGraphYRangeForPan()"));
        assertTrue(appcurve.contains("if(modernui)\n        graphPanYRangeLocked=true;"));
        assertTrue(appcurve.contains(
                "void JCurve::setmodernui(bool enabled) {\n    unlockGraphYRange();"));
        assertTrue(appcurve.contains(
                "void JCurve::setgraphhours(int hours) {\n    unlockGraphYRange();"));
        assertTrue(appcurve.contains(
                "void JCurve::xscaleGesture(float scalex,float midx) {\n"
                        + "    unlockGraphYRange();"));
        assertTrue(appcurve.contains(
                "int JCurve::mouseScale(float dx,float xold,float x) {\n"
                        + "     unlockGraphYRange();"));

        String renderer=nativeSource("curve","curve.cpp");
        assertTrue(renderer.contains(
                "if(!graphPanYRangeLocked&&(setend<starttime2||settime>=endtime))"));

        String bridge=nativeSource("curve","javacurve.cpp");
        assertTrue(bridge.contains("fromjava(lockGraphYRangeForPan)"));
        assertTrue(bridge.contains(
                "fromjava(settonow)(JNIEnv *env, jclass thiz) {\n"
                        + " appcurve.unlockGraphYRange();"));

        String settings=nativeSource("settings","javasettings.cpp");
        assertTrue(settings.contains(
                "fromjava(setGraphRange)(JNIEnv *env, jclass cl,jfloat glow,jfloat ghigh)"));
        assertTrue(settings.contains(
                "fromjava(setTargetRange)(JNIEnv *env, jclass cl,jfloat tlow,jfloat thigh)"));
        assertTrue(settings.contains("resetGraphPanYRangeLock();"));

        String natives=source("Natives.java");
        assertTrue(natives.contains("native void lockGraphYRangeForPan()"));
    }

    private static String source(String name) throws IOException {
        Path relative=Paths.get("src","main","java","tk","glucodata",name);
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }

    private static String nativeSource(String folder,String name) throws IOException {
        Path relative=Paths.get("src","main","cpp",folder,name);
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
