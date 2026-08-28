#pragma once

#include <algorithm>
#include <cstdint>

namespace graphpoints {

enum class RangeState : std::uint8_t {
    low,
    in_range,
    high
};

// Range states remain identifiable when colour perception is limited: low
// samples point down, in-range samples are round and high samples point up.
enum class MarkerShape : std::uint8_t {
    down_triangle,
    circle,
    up_triangle
};

constexpr RangeState range_state(const float value,const float targetlow,
                                 const float targethigh) {
    return value<targetlow?RangeState::low:
           (value>targethigh?RangeState::high:RangeState::in_range);
}

constexpr MarkerShape marker_shape(const RangeState state) {
    return state==RangeState::low?MarkerShape::down_triangle:
           (state==RangeState::high?MarkerShape::up_triangle:
                                    MarkerShape::circle);
}

constexpr float visible_hours(const std::uint32_t durationseconds) {
    return static_cast<float>(durationseconds)/3600.0f;
}

constexpr float density_scale(const std::uint32_t durationseconds) {
    return std::clamp((visible_hours(durationseconds)-3.0f)/21.0f,0.0f,1.0f);
}

// Samples stay legible at short ranges, then become deliberately quieter as
// the graph approaches a dense 24-hour overview. Discrete scans are slightly
// larger because they are normally much sparser than the stream curve.
constexpr float sample_radius(const float density,
                              const std::uint32_t durationseconds,
                              const bool discrete) {
    const float safeDensity=std::max(density,0.1f);
    const float radiusDp=2.45f-density_scale(durationseconds)*0.70f+
                         (discrete?0.25f:0.0f);
    return std::max(1.0f,safeDensity*radiusDp);
}

// Pixel-based spacing gives the same visual density to sensors with different
// sampling intervals and avoids turning a 24-hour curve into a bead string.
constexpr float minimum_spacing(const float density,
                                const std::uint32_t durationseconds) {
    const float safeDensity=std::max(density,0.1f);
    return safeDensity*(5.50f+density_scale(durationseconds)*1.50f);
}

constexpr bool should_draw_sample(const float x,const float lastdrawx,
                                  const float minspacing,const bool hasdrawn,
                                  const bool first,const bool last,
                                  const bool statechanged,
                                  const bool lastHasEmphasis) {
    if(last&&lastHasEmphasis)
        return false;
    if(first||last||statechanged||!hasdrawn)
        return true;
    const float distance=x>=lastdrawx?x-lastdrawx:lastdrawx-x;
    return distance>=minspacing;
}

static_assert(range_state(69.0f,70.0f,180.0f)==RangeState::low);
static_assert(range_state(70.0f,70.0f,180.0f)==RangeState::in_range);
static_assert(range_state(181.0f,70.0f,180.0f)==RangeState::high);
static_assert(marker_shape(RangeState::low)==MarkerShape::down_triangle);
static_assert(marker_shape(RangeState::in_range)==MarkerShape::circle);
static_assert(marker_shape(RangeState::high)==MarkerShape::up_triangle);
static_assert(sample_radius(1.0f,3U*3600U,false)>
              sample_radius(1.0f,24U*3600U,false));
static_assert(minimum_spacing(1.0f,3U*3600U)<
              minimum_spacing(1.0f,24U*3600U));
static_assert(should_draw_sample(10.0f,9.0f,6.0f,true,false,false,true,false));
static_assert(!should_draw_sample(10.0f,9.0f,6.0f,true,false,true,false,true));

} // namespace graphpoints
