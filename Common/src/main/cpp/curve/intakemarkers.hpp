#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace intakemarkers {

/** A glucose sample already transformed into the current graph's Y space. */
struct GlucosePoint {
    std::uint32_t time;
    float y;
};

// History samples normally arrive every 15 minutes. A 20 minute ceiling lets
// an event sit on that rendered segment, while refusing to bridge a real data
// gap. A lone nearby sample remains useful for a short period around the event.
inline constexpr std::uint32_t maxInterpolationGapSeconds=20U*60U;
inline constexpr std::uint32_t maxNearestDistanceSeconds=12U*60U;

constexpr float interpolate_y(const std::uint32_t beforeTime,
                              const float beforeY,
                              const std::uint32_t afterTime,
                              const float afterY,
                              const std::uint32_t eventTime) {
    return afterTime<=beforeTime?beforeY:
           beforeY+(afterY-beforeY)*
                   (static_cast<float>(eventTime-beforeTime)/
                    static_cast<float>(afterTime-beforeTime));
}

/**
 * Finds the glucose-line Y at an event time. Points must be time sorted.
 * Returns false when no clinically nearby glucose reading exists; callers can
 * then use a neutral in-plot fallback instead of implying a measured value.
 */
inline bool anchor_y(const std::vector<GlucosePoint> &points,
                     const std::uint32_t eventTime,float &result) {
    if(points.empty())
        return false;
    const auto after=std::lower_bound(points.begin(),points.end(),eventTime,
            [](const GlucosePoint &point,const std::uint32_t time) {
                return point.time<time;
            });
    if(after!=points.end()&&after->time==eventTime) {
        result=after->y;
        return std::isfinite(result);
    }
    const bool hasBefore=after!=points.begin();
    const bool hasAfter=after!=points.end();
    if(hasBefore&&hasAfter) {
        const auto before=std::prev(after);
        const std::uint32_t gap=after->time-before->time;
        if(gap<=maxInterpolationGapSeconds) {
            result=interpolate_y(before->time,before->y,after->time,
                                 after->y,eventTime);
            return std::isfinite(result);
        }
    }

    const GlucosePoint *nearest=nullptr;
    std::uint32_t nearestDistance=UINT32_MAX;
    if(hasBefore) {
        const auto before=std::prev(after);
        nearest=&*before;
        nearestDistance=eventTime-before->time;
    }
    if(hasAfter) {
        const std::uint32_t distance=after->time-eventTime;
        if(distance<nearestDistance) {
            nearest=&*after;
            nearestDistance=distance;
        }
    }
    if(nearest&&nearestDistance<=maxNearestDistanceSeconds&&
       std::isfinite(nearest->y)) {
        result=nearest->y;
        return true;
    }
    return false;
}

/**
 * Screen-space clustering deliberately ignores wall-clock distance. The same
 * two events may be a cluster in an eight-hour view and separate markers once
 * the user zooms in. The span guard prevents a chain of individually-close
 * events from joining two markers that are visibly far apart.
 */
constexpr bool joins_cluster(const float firstX,const float previousX,
                             const float nextX,const float adjacentDistance,
                             const float maximumSpan) {
    return nextX>=previousX&&nextX-previousX<=adjacentDistance&&
           nextX-firstX<=maximumSpan;
}

static_assert(interpolate_y(100U,10.0f,200U,20.0f,150U)==15.0f);
static_assert(joins_cluster(10.0f,20.0f,28.0f,10.0f,30.0f));
static_assert(!joins_cluster(10.0f,20.0f,31.0f,10.0f,30.0f));
static_assert(!joins_cluster(10.0f,35.0f,43.0f,10.0f,30.0f));

} // namespace intakemarkers
