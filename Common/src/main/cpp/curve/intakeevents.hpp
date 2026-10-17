#pragma once

#include <cstdint>
#include <vector>

enum IntakeGraphFlags : std::uint32_t {
    IntakeGraphMeal = 1U,
    IntakeGraphCarbsPresent = 1U << 1U,
    IntakeGraphRapidInsulin = 1U << 2U,
    IntakeGraphLongInsulin = 1U << 3U
};

enum class IntakeInsulinKind : std::uint8_t {
    None,
    Rapid,
    Long,
    Other
};

constexpr IntakeInsulinKind intakeInsulinKind(const std::uint32_t flags,
                                               const float units) {
    if(!(units>0.0f))
        return IntakeInsulinKind::None;
    const bool rapid=(flags&IntakeGraphRapidInsulin)!=0U;
    const bool longActing=(flags&IntakeGraphLongInsulin)!=0U;
    return rapid&&!longActing?IntakeInsulinKind::Rapid:
           longActing&&!rapid?IntakeInsulinKind::Long:
           IntakeInsulinKind::Other;
}

static_assert(intakeInsulinKind(IntakeGraphRapidInsulin,1.0f)==
              IntakeInsulinKind::Rapid);
static_assert(intakeInsulinKind(IntakeGraphLongInsulin,1.0f)==
              IntakeInsulinKind::Long);

struct IntakeGraphEvent {
    std::int32_t key;
    std::uint32_t time;
    float carbs;
    float insulin;
    std::uint32_t flags;
};

void replaceIntakeGraphEvents(std::vector<IntakeGraphEvent> events);
int intakeGraphEventAt(float x,float y);
std::vector<std::int32_t> intakeGraphEventsAt(float x,float y);
