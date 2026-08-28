#pragma once

#include <algorithm>

namespace graphlayout {

// Native graph space is measured in physical pixels, while the dashboard
// cards and breakpoints are density independent. Keeping this policy here
// prevents the plot from using phone-sized gutters on an unfolded display.
constexpr float horizontal_inset_dp(const float logicalWidthDp) {
    return logicalWidthDp<360.0f?14.0f:
           (logicalWidthDp<600.0f?18.0f:
           (logicalWidthDp<1200.0f?24.0f:28.0f));
}

constexpr float horizontal_inset_px(const float widthPx,
                                    const float density) {
    const float safeDensity=std::max(density,0.1f);
    return horizontal_inset_dp(widthPx/safeDensity)*safeDensity;
}

static_assert(horizontal_inset_dp(320.0f)==14.0f);
static_assert(horizontal_inset_dp(411.0f)==18.0f);
static_assert(horizontal_inset_dp(840.0f)==24.0f);
static_assert(horizontal_inset_dp(1400.0f)==28.0f);

} // namespace graphlayout
