package tk.glucodata;

/** Pure state machine that keeps one gesture on one graph axis. */
final class GraphGestureState {
    enum Axis {
        UNDECIDED,
        HORIZONTAL_PAN,
        VERTICAL_IGNORED
    }

    private static final float AXIS_DOMINANCE_RATIO=1.25f;
    private static final float AMBIGUOUS_LOCK_SLOP_MULTIPLIER=2.0f;

    private final float touchSlop;
    private float downX;
    private float downY;
    private Axis axis=Axis.UNDECIDED;
    private boolean pinchSequence;

    GraphGestureState(float touchSlop) {
        this.touchSlop=Math.max(0.0f,touchSlop);
    }

    void beginSingleFinger(float x,float y) {
        downX=x;
        downY=y;
        axis=Axis.UNDECIDED;
        pinchSequence=false;
    }

    void beginPinch() {
        axis=Axis.UNDECIDED;
        pinchSequence=true;
    }

    boolean isPinchSequence() {
        return pinchSequence;
    }

    Axis updateSingleFinger(float x,float y) {
        if(pinchSequence)
            return Axis.UNDECIDED;
        axis=resolveAxis(axis,x-downX,y-downY,touchSlop);
        return axis;
    }

    boolean allowsHorizontalFling(float velocityX,float velocityY,float minimumVelocity) {
        return !pinchSequence&&axis==Axis.HORIZONTAL_PAN
                &&Math.abs(velocityX)>minimumVelocity
                &&Math.abs(velocityX)>Math.abs(velocityY);
    }

    void endSequence() {
        axis=Axis.UNDECIDED;
        pinchSequence=false;
    }

    static Axis resolveAxis(Axis current,float totalX,float totalY,float touchSlop) {
        if(current!=Axis.UNDECIDED)
            return current;
        final float absX=Math.abs(totalX);
        final float absY=Math.abs(totalY);
        final float primary=Math.max(absX,absY);
        final float safeSlop=Math.max(0.0f,touchSlop);
        if(primary==0.0f||primary<safeSlop)
            return Axis.UNDECIDED;
        if(absX>=absY*AXIS_DOMINANCE_RATIO)
            return Axis.HORIZONTAL_PAN;
        if(primary>=safeSlop*AMBIGUOUS_LOCK_SLOP_MULTIPLIER) {
            if(absY>=absX*AXIS_DOMINANCE_RATIO)
                return Axis.VERTICAL_IGNORED;
            return Axis.HORIZONTAL_PAN;
        }
        return Axis.UNDECIDED;
    }
}
