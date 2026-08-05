from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from app.media import PreparedAudio, PreparedImage
from app.openrouter import (
    AnalysisError,
    MealChatHistoryEntry,
    OpenRouterMealAnalyzer,
    OpenRouterMealChatAnalyzer,
)
from app.schemas import MEAL_CHAT_JSON_SCHEMA
from conftest import make_settings


def test_openrouter_vision_uses_strict_schema_two_images_and_zdr(tmp_path):
    captured: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer dummy-openrouter-key"
        payload = json.loads(request.content)
        captured.append(payload)
        result = {
            "meal_name": "Pasta",
            "meal_description": "A plate of pasta",
            "estimated_carbs_g": 70,
            "carbs_low_g": 55,
            "carbs_high_g": 90,
            "confidence": 0.7,
            "items": [{"name": "Pasta", "portion_g": 250, "carbs_g": 70}],
            "assumptions": ["No scale reference was visible."],
            "warnings": [],
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(result)}}]},
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealAnalyzer(settings, http_client=client)

    async def run():
        return await analyzer.analyze(
            "pasta",
            [PreparedImage(b"one"), PreparedImage(b"two")],
            None,
        )

    analysis, transcript = asyncio.run(run())
    asyncio.run(client.aclose())
    assert transcript == ""
    assert analysis.estimated_carbs_g == 70
    assert any("confirm" in warning.lower() for warning in analysis.warnings)
    assert len(captured) == 1
    payload = captured[0]
    assert payload["response_format"]["json_schema"]["strict"] is True
    assert payload["provider"] == {
        "require_parameters": True,
        "data_collection": "deny",
        "zdr": True,
    }
    images = [
        part
        for part in payload["messages"][1]["content"]
        if part["type"] == "image_url"
    ]
    assert len(images) == 2
    assert all(
        image["image_url"]["url"].startswith("data:image/jpeg;base64,")
        for image in images
    )


def test_openrouter_audio_is_transcribed_before_meal_analysis(tmp_path):
    models: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        models.append(payload["model"])
        if payload["model"] == "test/audio-model":
            audio_part = payload["messages"][1]["content"][1]
            assert audio_part["type"] == "input_audio"
            assert audio_part["input_audio"]["format"] == "m4a"
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": "Two slices of bread"}}]},
            )
        result = {
            "meal_name": "Bread",
            "meal_description": "Two slices of bread",
            "estimated_carbs_g": 30,
            "carbs_low_g": 24,
            "carbs_high_g": 38,
            "confidence": 0.82,
            "items": [{"name": "Bread", "portion_g": 60, "carbs_g": 30}],
            "assumptions": [],
            "warnings": [],
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(result)}}]},
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealAnalyzer(settings, http_client=client)

    async def run():
        return await analyzer.analyze("", [], PreparedAudio(b"audio", "m4a"))

    analysis, transcript = asyncio.run(run())
    asyncio.run(client.aclose())
    assert transcript == "Two slices of bread"
    assert analysis.meal_name == "Bread"
    assert models == ["test/audio-model", "test/vision-model"]


def test_openrouter_meal_chat_uses_history_many_images_strict_schema_and_zdr(
    tmp_path,
):
    captured: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        captured.append(payload)
        result = {
            "assistant_message": "Updated to half a bowl. Ready to confirm.",
            "proposal": {
                "meal_name": "Rice bowl",
                "meal_description": "Half a rice bowl",
                "total_portion_g": 210,
                "items": [
                    {"name": "Cooked rice", "portion_g": 110, "carbs_g": 31}
                ],
                "estimated_carbs_g": 31,
                "carbs_low_g": 25,
                "carbs_high_g": 39,
                "confidence": 0.84,
                "warnings": [],
            },
            "ready_to_confirm": True,
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(result)}}]},
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)
    history = [
        MealChatHistoryEntry(role="user", text="A rice bowl"),
        MealChatHistoryEntry(
            role="assistant",
            text="I estimated a full bowl.",
            proposal_json='{"estimated_carbs_g":52}',
        ),
    ]

    async def run():
        return await analyzer.chat(
            history,
            "Correction: half a bowl",
            [PreparedImage(b"one"), PreparedImage(b"two"), PreparedImage(b"three")],
            None,
        )

    result, transcript = asyncio.run(run())
    asyncio.run(client.aclose())
    assert transcript == ""
    assert result.ready_to_confirm is True
    assert result.proposal.estimated_carbs_g == 31
    assert any("confirm" in warning.lower() for warning in result.proposal.warnings)

    assert len(captured) == 1
    payload = captured[0]
    assert payload["model"] == "test/meal-chat-model"
    assert payload["provider"] == {
        "require_parameters": True,
        "data_collection": "deny",
        "zdr": True,
    }
    assert payload["response_format"]["json_schema"]["strict"] is True
    assert payload["response_format"]["json_schema"]["schema"][
        "additionalProperties"
    ] is False
    assert [message["role"] for message in payload["messages"]] == [
        "system",
        "user",
        "assistant",
        "user",
    ]
    system_prompt = payload["messages"][0]["content"]
    assert "recognizable consumed" in system_prompt
    assert "ready_to_confirm true" in system_prompt
    assert "Default to a best-effort proposal" in system_prompt
    assert "Any recognizable consumed" in system_prompt
    assert "carbohydrate amount, that alone is sufficient" in system_prompt
    assert "Never ask for an optional weight" in system_prompt
    final_parts = payload["messages"][-1]["content"]
    assert len([part for part in final_parts if part["type"] == "image_url"]) == 3


def test_meal_chat_absorption_contract_allows_honest_null_estimates(tmp_path):
    proposal_schema = MEAL_CHAT_JSON_SCHEMA["properties"]["proposal"]["anyOf"][0]
    for field in (
        "absorption_speed",
        "absorption_peak_minutes",
        "absorption_duration_minutes",
        "absorption_confidence",
    ):
        assert field in proposal_schema["required"]
        assert {item["type"] for item in proposal_schema["properties"][field]["anyOf"]} \
            >= {"null"}

    def handler(_request: httpx.Request) -> httpx.Response:
        result = {
            "assistant_message": "The amount is known; absorption timing is uncertain.",
            "proposal": {
                "meal_name": "Mixed homemade meal",
                "meal_description": "One serving of a mixed homemade meal",
                "total_portion_g": 300,
                "items": [
                    {
                        "name": "Mixed meal",
                        "portion_g": 300,
                        "carbs_g": 35,
                        "estimated_protein_g": None,
                        "estimated_fat_g": None,
                        "estimated_fiber_g": None,
                    }
                ],
                "estimated_carbs_g": 35,
                "carbs_low_g": 25,
                "carbs_high_g": 48,
                "confidence": 0.55,
                "absorption_speed": None,
                "absorption_peak_minutes": None,
                "absorption_duration_minutes": None,
                "absorption_confidence": None,
                "estimated_protein_g": None,
                "estimated_fat_g": None,
                "estimated_fiber_g": None,
                "warnings": ["Ingredients are not known."],
            },
            "ready_to_confirm": True,
        }
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(result)}}]}
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)
    result, _ = asyncio.run(analyzer.chat([], "One serving of homemade food", [], None))
    asyncio.run(client.aclose())

    assert result.ready_to_confirm is True
    assert result.proposal is not None
    assert result.proposal.absorption_speed is None
    assert result.proposal.absorption_peak_minutes is None
    assert result.proposal.absorption_duration_minutes is None
    assert result.proposal.absorption_confidence is None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("absorption_speed", -0.01),
        ("absorption_speed", 1.01),
        ("absorption_confidence", -0.01),
        ("absorption_confidence", 1.01),
    ],
)
def test_meal_chat_rejects_out_of_range_absorption_ratios(tmp_path, field, value):
    def handler(_request: httpx.Request) -> httpx.Response:
        proposal = {
            "meal_name": "Bread",
            "meal_description": "One slice of bread",
            "total_portion_g": 35,
            "items": [{"name": "Bread", "portion_g": 35, "carbs_g": 17}],
            "estimated_carbs_g": 17,
            "carbs_low_g": 14,
            "carbs_high_g": 20,
            "confidence": 0.7,
            "absorption_speed": 0.7,
            "absorption_peak_minutes": 60,
            "absorption_duration_minutes": 180,
            "absorption_confidence": 0.6,
            "warnings": [],
        }
        proposal[field] = value
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "assistant_message": "Ready.",
                                    "proposal": proposal,
                                    "ready_to_confirm": True,
                                }
                            )
                        }
                    }
                ]
            },
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)
    with pytest.raises(AnalysisError, match="failed schema validation"):
        asyncio.run(analyzer.chat([], "One slice of bread", [], None))
    asyncio.run(client.aclose())


def test_openrouter_promotes_complete_declarative_revision_to_ready(tmp_path):
    def handler(_request: httpx.Request) -> httpx.Response:
        provider_result = {
            "assistant_message": (
                "Updated: you ate 50 g out of the 180 g serving. "
                "The estimate is 10 g carbohydrate. Ready to save."
            ),
            "proposal": {
                "meal_name": "Cooked rice",
                "meal_description": "50 g of cooked rice",
                "total_portion_g": 50,
                "items": [
                    {"name": "Cooked rice", "portion_g": 50, "carbs_g": 10}
                ],
                "estimated_carbs_g": 10,
                "carbs_low_g": 9,
                "carbs_high_g": 12,
                "confidence": 0.92,
                "warnings": [],
            },
            # Provider edge case: content and proposal are complete, but this flag
            # contradicts them.
            "ready_to_confirm": False,
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(provider_result)}}]},
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(
        analyzer.chat([], "I ate only 50 g out of that 180 g serving", [], None)
    )
    asyncio.run(client.aclose())

    assert result.proposal is not None
    assert result.proposal.total_portion_g == 50
    assert result.proposal.estimated_carbs_g == 10
    assert result.ready_to_confirm is True


@pytest.mark.parametrize(
    "assistant_message",
    [
        "How much sauce did you eat?",
        "Please specify the unknown sauce amount before saving.",
        "Нужно уточнить количество соуса перед сохранением.",
    ],
)
@pytest.mark.parametrize("provider_ready", [False, True])
def test_openrouter_promotes_usable_draft_even_when_provider_asks_optional_question(
    tmp_path,
    assistant_message,
    provider_ready,
):
    def handler(_request: httpx.Request) -> httpx.Response:
        provider_result = {
            "assistant_message": assistant_message,
            "proposal": {
                "meal_name": "Rice with sauce",
                "meal_description": "50 g rice; sauce amount is not known yet",
                "total_portion_g": 50,
                "items": [
                    {"name": "Cooked rice", "portion_g": 50, "carbs_g": 10}
                ],
                "estimated_carbs_g": 10,
                "carbs_low_g": 9,
                "carbs_high_g": 20,
                "confidence": 0.5,
                "warnings": ["Sauce is not included until its amount is known."],
            },
            "ready_to_confirm": provider_ready,
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(provider_result)}}]},
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], "There was sauce too", [], None))
    asyncio.run(client.aclose())

    assert result.proposal is not None
    assert result.ready_to_confirm is True
    assert result.assistant_message != assistant_message


def test_openrouter_retries_explicit_carbs_and_returns_generic_ready_proposal(tmp_path):
    captured: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content))
        if len(captured) == 1:
            result = {
                "assistant_message": "What food was it?",
                "proposal": None,
                "ready_to_confirm": False,
            }
        else:
            result = {
                "assistant_message": "Recorded the carbohydrate amount as provided.",
                "proposal": {
                    "meal_name": "Meal",
                    "meal_description": "Meal with user-entered carbohydrate amount",
                    "total_portion_g": 0,
                    "items": [{"name": "Unspecified meal", "portion_g": 0, "carbs_g": 45}],
                    "estimated_carbs_g": 45,
                    "carbs_low_g": 45,
                    "carbs_high_g": 45,
                    "confidence": 1,
                    "warnings": ["Food identity was not provided."],
                },
                "ready_to_confirm": True,
            }
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(result)}}]}
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], "45 g carbs", [], None))
    asyncio.run(client.aclose())

    assert len(captured) == 2
    assert captured[1]["messages"][0]["role"] == "system"
    retry_system = " ".join(captured[1]["messages"][0]["content"].split())
    assert "best-effort complete proposal" in retry_system
    assert captured[1]["messages"][-1]["role"] == "user"
    assert result.ready_to_confirm is True
    assert result.proposal is not None
    assert result.proposal.estimated_carbs_g == 45
    assert result.proposal.total_portion_g == 0


def test_openrouter_accepts_top_level_carbs_without_item_or_portion(tmp_path):
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        result = {
            "assistant_message": "The exact food is unknown, but 30 g carbs can be saved.",
            "proposal": {
                "meal_name": "Meal",
                "meal_description": "Meal with 30 g user-entered carbohydrate",
                "total_portion_g": 0,
                "items": [],
                "estimated_carbs_g": 30,
                "carbs_low_g": 30,
                "carbs_high_g": 30,
                "confidence": 1,
                "warnings": ["Food identity and portion were not provided."],
            },
            "ready_to_confirm": False,
        }
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(result)}}]}
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], "30 g carbs", [], None))
    asyncio.run(client.aclose())

    assert calls == 1
    assert result.ready_to_confirm is True
    assert result.proposal is not None
    assert result.proposal.items == []
    assert result.proposal.estimated_carbs_g == 30


def test_openrouter_retries_recognized_but_zero_portion_draft_once(tmp_path):
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            result = {
                "assistant_message": "How much rice did you eat?",
                "proposal": {
                    "meal_name": "Rice",
                    "meal_description": "Rice; amount unknown",
                    "total_portion_g": 0,
                    "items": [{"name": "Rice", "portion_g": 0, "carbs_g": 0}],
                    "estimated_carbs_g": 0,
                    "carbs_low_g": 0,
                    "carbs_high_g": 0,
                    "confidence": 0.1,
                    "warnings": ["Amount was not provided."],
                },
                "ready_to_confirm": False,
            }
        else:
            result = {
                "assistant_message": "Estimated one typical serving. Ready to save.",
                "proposal": {
                    "meal_name": "Rice",
                    "meal_description": "One estimated typical serving of cooked rice",
                    "total_portion_g": 180,
                    "items": [{"name": "Cooked rice", "portion_g": 180, "carbs_g": 50}],
                    "estimated_carbs_g": 50,
                    "carbs_low_g": 32,
                    "carbs_high_g": 70,
                    "confidence": 0.35,
                    "warnings": ["A typical serving was assumed."],
                },
                "ready_to_confirm": True,
            }
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(result)}}]}
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], "rice", [], None))
    asyncio.run(client.aclose())

    assert calls == 2
    assert result.ready_to_confirm is True
    assert result.proposal is not None
    assert result.proposal.total_portion_g == 180


@pytest.mark.parametrize(
    ("meal_text", "images"),
    [
        ("apple", []),
        ("", [PreparedImage(b"recognizable-food-photo")]),
    ],
    ids=["plain-food-name", "image-only"],
)
def test_openrouter_retries_null_draft_for_any_meal_evidence(
    tmp_path, meal_text, images
):
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            result = {
                "assistant_message": "I need more information.",
                "proposal": None,
                "ready_to_confirm": False,
            }
        else:
            result = {
                "assistant_message": "Estimated a typical serving. Ready to save.",
                "proposal": {
                    "meal_name": "Apple",
                    "meal_description": "One estimated medium apple",
                    "total_portion_g": 180,
                    "items": [{"name": "Apple", "portion_g": 180, "carbs_g": 25}],
                    "estimated_carbs_g": 25,
                    "carbs_low_g": 16,
                    "carbs_high_g": 34,
                    "confidence": 0.4,
                    "warnings": ["A typical serving was assumed."],
                },
                "ready_to_confirm": True,
            }
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(result)}}]}
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], meal_text, images, None))
    asyncio.run(client.aclose())

    assert calls == 2
    assert result.ready_to_confirm is True
    assert result.proposal is not None
    assert result.proposal.meal_name == "Apple"


@pytest.mark.parametrize("cancel_text", ["cancel", "не сохраняй"])
def test_openrouter_cancel_only_turn_clears_stale_proposal_without_retry(
    tmp_path, cancel_text
):
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        stale = {
            "assistant_message": "The previous rice estimate is still ready.",
            "proposal": {
                "meal_name": "Rice",
                "meal_description": "One bowl of rice",
                "total_portion_g": 180,
                "items": [{"name": "Rice", "portion_g": 180, "carbs_g": 50}],
                "estimated_carbs_g": 50,
                "carbs_low_g": 40,
                "carbs_high_g": 62,
                "confidence": 0.6,
                "warnings": [],
            },
            "ready_to_confirm": True,
        }
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(stale)}}]}
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)
    history = [
        MealChatHistoryEntry(role="user", text="rice"),
        MealChatHistoryEntry(
            role="assistant",
            text="Ready.",
            proposal_json='{"meal_name":"Rice","estimated_carbs_g":50}',
        ),
    ]

    result, _ = asyncio.run(analyzer.chat(history, cancel_text, [], None))
    asyncio.run(client.aclose())

    assert calls == 1
    assert result.ready_to_confirm is False
    assert result.proposal is None
    assert "previous rice" not in result.assistant_message.lower()


def test_openrouter_keeps_genuinely_unusable_input_not_ready_after_one_hidden_retry(tmp_path):
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        result = {
            "assistant_message": "I could not identify anything to add. What did you eat?",
            "proposal": None,
            "ready_to_confirm": False,
        }
        return httpx.Response(
            200, json={"choices": [{"message": {"content": json.dumps(result)}}]}
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], "abracadabra", [], None))
    asyncio.run(client.aclose())

    assert calls == 2
    assert result.ready_to_confirm is False
    assert result.proposal is None


def test_forced_retry_rate_limit_falls_back_to_first_valid_result(tmp_path):
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            incomplete = {
                "assistant_message": "I recognized rice but could not estimate a serving.",
                "proposal": {
                    "meal_name": "Rice",
                    "meal_description": "Rice; serving unknown",
                    "total_portion_g": 0,
                    "items": [{"name": "Rice", "portion_g": 0, "carbs_g": 0}],
                    "estimated_carbs_g": 0,
                    "carbs_low_g": 0,
                    "carbs_high_g": 0,
                    "confidence": 0.1,
                    "warnings": ["Serving is unknown."],
                },
                "ready_to_confirm": False,
            }
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": json.dumps(incomplete)}}]},
            )
        return httpx.Response(429, json={"error": {"message": "rate limited"}})

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], "rice", [], None))
    asyncio.run(client.aclose())

    assert calls == 2
    assert result.proposal is not None
    assert result.proposal.meal_name == "Rice"
    assert result.ready_to_confirm is False


@pytest.mark.parametrize("provider_ready", [False, True])
def test_openrouter_does_not_promote_structurally_incomplete_proposal(
    tmp_path, provider_ready
):
    def handler(_request: httpx.Request) -> httpx.Response:
        provider_result = {
            "assistant_message": "I recognized rice, but no consumed amount was supplied.",
            "proposal": {
                "meal_name": "Cooked rice",
                "meal_description": "Cooked rice with unknown consumed amount",
                "total_portion_g": 0,
                "items": [{"name": "Cooked rice", "portion_g": 0, "carbs_g": 0}],
                "estimated_carbs_g": 0,
                "carbs_low_g": 0,
                "carbs_high_g": 0,
                "confidence": 0.1,
                "warnings": ["Consumed amount is missing."],
            },
            "ready_to_confirm": provider_ready,
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(provider_result)}}]},
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    result, _ = asyncio.run(analyzer.chat([], "rice", [], None))
    asyncio.run(client.aclose())

    assert result.ready_to_confirm is False


def test_openrouter_meal_chat_fails_closed_on_insulin_output(tmp_path):
    def handler(_request: httpx.Request) -> httpx.Response:
        unsafe = {
            "assistant_message": "You should change your insulin bolus.",
            "proposal": None,
            "ready_to_confirm": False,
        }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(unsafe)}}]},
        )

    settings = make_settings(tmp_path, openrouter_api_key="dummy-openrouter-key")
    client = httpx.AsyncClient(
        base_url="https://openrouter.invalid/api/v1/",
        transport=httpx.MockTransport(handler),
    )
    analyzer = OpenRouterMealChatAnalyzer(settings, http_client=client)

    async def run():
        return await analyzer.chat([], "an apple", [], None)

    with pytest.raises(AnalysisError, match="safety validation"):
        asyncio.run(run())
    asyncio.run(client.aclose())
