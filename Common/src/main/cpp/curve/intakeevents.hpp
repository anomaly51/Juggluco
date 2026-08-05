#pragma once

#include <cstdint>
#include <vector>

enum IntakeTimelineFlags : std::uint32_t {
    IntakeTimelineMeal = 1U,
    IntakeTimelineCarbsPresent = 1U << 1U,
    IntakeTimelineRapidInsulin = 1U << 2U,
    IntakeTimelineLongInsulin = 1U << 3U
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
    const bool rapid=(flags&IntakeTimelineRapidInsulin)!=0U;
    const bool longActing=(flags&IntakeTimelineLongInsulin)!=0U;
    return rapid&&!longActing?IntakeInsulinKind::Rapid:
           longActing&&!rapid?IntakeInsulinKind::Long:
           IntakeInsulinKind::Other;
}

static_assert(intakeInsulinKind(IntakeTimelineRapidInsulin,1.0f)==
              IntakeInsulinKind::Rapid);
static_assert(intakeInsulinKind(IntakeTimelineLongInsulin,1.0f)==
              IntakeInsulinKind::Long);

struct IntakeTimelineEvent {
    std::int32_t key;
    std::uint32_t time;
    float carbs;
    float insulin;
    std::uint32_t flags;
};

void replaceIntakeTimelineEvents(std::vector<IntakeTimelineEvent> events);
int intakeTimelineEventAt(float x,float y);
std::vector<std::int32_t> intakeTimelineEventsAt(float x,float y);
