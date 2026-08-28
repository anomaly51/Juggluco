from __future__ import annotations

import re
from dataclasses import dataclass


# A dose is deliberately a plain decimal, never a prefix of a longer token.
# This rejects 1000, 4.567, 1,000 and 1e2 instead of truncating them.
_NUMBER = r"(?:[1-9]\d{0,2}(?:[.,]\d{1,2})?|0[.,]\d{1,2})"
_UNIT = (
    r"(?:units?|iu|u|единицами|единицу|единицы|единица|единиц|ед\.?)"
)
_RU_INSULIN_WORD = r"инсулин(?:а|у|ом|е)?"
_RU_FAST_WORD = r"быстр(?:ый|ого|ому|ым|ом|ая|ой|ую|ые|ых|ыми)"
_RU_SLOW_WORD = r"медленн(?:ый|ого|ому|ым|ом|ая|ой|ую|ые|ых|ыми)"
_FAST_INSULIN_ALIAS = (
    rf"(?:fast(?:-acting)?\s+insulin|insulin\s+fast(?:-acting)?|"
    rf"{_RU_FAST_WORD}\s+{_RU_INSULIN_WORD}|"
    rf"{_RU_INSULIN_WORD}\s+{_RU_FAST_WORD})"
)
_SLOW_INSULIN_ALIAS = (
    rf"(?:slow(?:-acting)?\s+insulin|insulin\s+slow(?:-acting)?|"
    rf"{_RU_SLOW_WORD}\s+{_RU_INSULIN_WORD}|"
    rf"{_RU_INSULIN_WORD}\s+{_RU_SLOW_WORD})"
)
_RAPID = (
    rf"(?:{_FAST_INSULIN_ALIAS}|nov[oa][\s-]?rapid|novorapid|rapid|"
    r"нов[оа][\s-]?рапид(?:а|ом|у)?|"
    r"новорапид(?:а|ом|у)?|рапид(?:а|ом|у)?)"
)
_TRESIBA = rf"(?:{_SLOW_INSULIN_ALIAS}|tresiba|тресиб(?:а|ы|у|ой)?)"
_PRODUCT_BODY = rf"(?:{_RAPID}|{_TRESIBA})"
_PRODUCT = rf"(?<![\w])(?P<product>{_PRODUCT_BODY})(?![\w])"
_BOUNDED_NUMBER = rf"(?<![\d.,\w])(?P<units>{_NUMBER})(?![\d.,eE\w])"


def _build_ru_spoken_numbers() -> dict[str, float]:
    variants: dict[int, tuple[str, ...]] = {
        0: ("ноль", "нуль"),
        1: ("один", "одна", "одну"),
        2: ("два", "две"),
        3: ("три",),
        4: ("четыре",),
        5: ("пять",),
        6: ("шесть",),
        7: ("семь",),
        8: ("восемь",),
        9: ("девять",),
        10: ("десять",),
        11: ("одиннадцать",),
        12: ("двенадцать",),
        13: ("тринадцать",),
        14: ("четырнадцать",),
        15: ("пятнадцать",),
        16: ("шестнадцать",),
        17: ("семнадцать",),
        18: ("восемнадцать",),
        19: ("девятнадцать",),
    }
    tens = {
        20: "двадцать",
        30: "тридцать",
        40: "сорок",
        50: "пятьдесят",
        60: "шестьдесят",
        70: "семьдесят",
        80: "восемьдесят",
        90: "девяносто",
    }
    for value, word in tens.items():
        variants[value] = (word,)
        for remainder in range(1, 10):
            variants[value + remainder] = tuple(
                f"{word} {ending}" for ending in variants[remainder]
            )
    variants[100] = ("сто",)

    spoken: dict[str, float] = {
        phrase: float(value)
        for value, phrases in variants.items()
        for phrase in phrases
    }
    spoken.update({"половина": 0.5, "полтора": 1.5, "полторы": 1.5})
    for value in range(1, 100):
        for phrase in variants[value]:
            spoken[f"{phrase} с половиной"] = value + 0.5
    return spoken


_RU_SPOKEN_NUMBERS = _build_ru_spoken_numbers()
_RU_SPOKEN_NUMBER = "(?:" + "|".join(
    re.escape(value)
    for value in sorted(_RU_SPOKEN_NUMBERS, key=len, reverse=True)
) + ")"
_RU_INFLECTED_DOSE_NUMBERS: dict[str, float] = {
    "первого": 1,
    "второго": 2,
    "третьего": 3,
    "четвертого": 4,
    "четвёртого": 4,
    "пятого": 5,
    "шестого": 6,
    "седьмого": 7,
    "восьмого": 8,
    "девятого": 9,
    "десятого": 10,
    "одиннадцатого": 11,
    "двенадцатого": 12,
    "тринадцатого": 13,
    "четырнадцатого": 14,
    "пятнадцатого": 15,
    "шестнадцатого": 16,
    "семнадцатого": 17,
    "восемнадцатого": 18,
    "девятнадцатого": 19,
    "двадцатого": 20,
}
_PRODUCT_EVIDENCE_WORD = re.compile(r"[A-Za-zА-Яа-яЁё]+")
_SEMANTIC_DOSE_TOKEN_BODY = (
    rf"(?:{_NUMBER}|{_RU_SPOKEN_NUMBER}|"
    + "|".join(
        re.escape(value)
        for value in sorted(
            _RU_INFLECTED_DOSE_NUMBERS,
            key=len,
            reverse=True,
        )
    )
    + r")"
)
_SEMANTIC_BOUNDED_DOSE_TOKEN = re.compile(
    rf"(?<![\d\w]){_SEMANTIC_DOSE_TOKEN_BODY}(?![\d\w])",
    re.IGNORECASE,
)
_SEMANTIC_DOSE_CONTEXT_END = r"(?![\d\w]|[.,]\d)"
_LOCALLY_NEGATED_DOSE_PREFIX = re.compile(
    r"(?:\bnot|\bне)\s*[,;:\-–—]*\s*$",
    re.IGNORECASE,
)

# Whisper can confuse the unstressed vowels in Russian pronunciations of
# NovoRapid.  Keep this allowlist intentionally finite.  These aliases are not
# products on their own: `_normalize_rapid_asr_aliases` accepts them only when
# they occupy a product slot immediately beside a complete, bounded dose.
_RAPID_ASR_ALIASES = (
    "на воропид",
    "на воропида",
    "на воропидом",
    "на воропиду",
    "наворопид",
    "наворопида",
    "наворопидом",
    "наворопиду",
    "навропид",
    "навропида",
    "навропидом",
    "навропиду",
    "нава рапид",
    "нава рапида",
    "нава рапидом",
    "нава рапиду",
    "наварапид",
    "наварапида",
    "наварапидом",
    "наварапиду",
    "новарапид",
    "новарапида",
    "новарапидом",
    "новарапиду",
)
_RAPID_ASR_ALIAS = "(?:" + "|".join(
    re.escape(value)
    for value in sorted(_RAPID_ASR_ALIASES, key=len, reverse=True)
) + ")"
_EXPLICIT_DOSE_TOKEN = rf"(?:{_NUMBER}|{_RU_SPOKEN_NUMBER})(?:\s*{_UNIT})?"
_DOSE_BEFORE_RAPID_ASR_ALIAS = re.compile(
    rf"(?P<dose>(?<![\d.,\w]){_EXPLICIT_DOSE_TOKEN})"
    rf"(?P<gap>\s+)(?P<alias>{_RAPID_ASR_ALIAS})(?![\w])",
    re.IGNORECASE,
)
_RAPID_ASR_ALIAS_BEFORE_DOSE = re.compile(
    rf"(?<![\w])(?P<alias>{_RAPID_ASR_ALIAS})"
    rf"(?P<gap>\s+)(?P<dose>{_EXPLICIT_DOSE_TOKEN})(?![\d.,eE\w])",
    re.IGNORECASE,
)
_SELF_INJECTION_ACTION_ASR = re.compile(
    r"(?<![\w])я\s+около(?=\s)",
    re.IGNORECASE,
)
_RU_SPOKEN_WITH_UNIT = re.compile(
    rf"(?<![\w])(?P<number>{_RU_SPOKEN_NUMBER})(?P<unit>\s+{_UNIT})(?![\w])",
    re.IGNORECASE,
)
_RU_SPOKEN_BEFORE_PRODUCT = re.compile(
    rf"(?<![\w])(?P<number>{_RU_SPOKEN_NUMBER})(?P<gap>\s+)"
    rf"(?P<product>{_PRODUCT_BODY})(?![\w])",
    re.IGNORECASE,
)
_RU_PRODUCT_BEFORE_SPOKEN = re.compile(
    rf"(?<![\w])(?P<product>{_PRODUCT_BODY})(?P<gap>\s+)"
    rf"(?P<number>{_RU_SPOKEN_NUMBER})(?![\w])",
    re.IGNORECASE,
)

_DOSE_BEFORE_PRODUCT = re.compile(
    rf"{_BOUNDED_NUMBER}(?:\s*{_UNIT})?\s+{_PRODUCT}", re.IGNORECASE
)
_PRODUCT_BEFORE_DOSE = re.compile(
    rf"{_PRODUCT}\s+{_BOUNDED_NUMBER}(?:\s*{_UNIT})?", re.IGNORECASE
)
_KNOWN_PRODUCT = re.compile(
    rf"(?<![\w]){_PRODUCT_BODY}(?![\w])", re.IGNORECASE
)
_INSULIN_LIKE_TOKEN = re.compile(
    r"(?<![\w])(?:\w*(?:rapid|tresiba|рапид|тресиб)\w*|"
    r"humalog|novolog|fiasp|lantus|levemir|toujeo|apidra|"
    r"хумалог|новолог|фиасп|лантус|левемир|туджео|апидра)(?![\w])",
    re.IGNORECASE,
)
_GENERIC_INSULIN = re.compile(
    r"(?<![\w])(?:insulin\w*|bolus\w*|инсулин\w*|болюс\w*|"
    r"укол\w*|подкол\w*)(?![\w])",
    re.IGNORECASE,
)
_GENERIC_PRODUCT = re.compile(
    r"(?<![\w])(?:insulin\w*|bolus\w*|инсулин\w*|болюс\w*)(?![\w])",
    re.IGNORECASE,
)
_UNATTACHED_DOSE = re.compile(
    rf"(?<![\d.,\w]){_NUMBER}(?![\d.,eE\w])\s*{_UNIT}(?![\w])",
    re.IGNORECASE,
)

_QUESTION = re.compile(
    r"\?|^\s*(?:did|do|does|am|is|are|was|were|should|could|would|can|"
    r"may|what|when|how|сколько|нужно\s+ли|можно\s+ли|а\s+я\s+)\b",
    re.IGNORECASE,
)
_INFO_REQUEST = re.compile(
    r"\b(?:(?:have|had|got)\s+(?:a\s+)?(?:question|thought|idea|problem)\b|"
    r"(?:want|need|would\s+like)\s+to\s+know\b|"
    r"(?:i|we)(?:['’]d|\s+would)\s+like\s+(?:to\s+know|information|details)\b|"
    r"(?:i|we)\s+(?:need|want)\s+(?:information|details)\b|"
    r"(?:i\s+)?wonder(?:ing)?\b|curious\b|tell\s+me\b|"
    r"(?:estimate|calculate|analy[sz]e)\b|"
    r"(?:есть|у\s+меня)\s+вопрос\b|хочу\s+узнать\b|расскажи\w*\b|"
    r"хотел(?:а)?\s+бы\s+узнать\b|интересно\b|"
    r"(?:посчитай|оцени|проанализируй)\w*\b)",
    re.IGNORECASE,
)
_FUTURE_OR_PLAN = re.compile(
    r"\b(?:will|shall|going\s+to|tomorrow|"
    r"next\s+(?:day|week|month|year|monday|tuesday|wednesday|thursday|"
    r"friday|saturday|sunday)|plans?|planned|planning|"
    r"intends?|intended|intending|about\s+to|was\s+about\s+to|"
    r"were\s+about\s+to|wanted\s+to|(?:was|were)\s+supposed\s+to|"
    r"needed\s+to|thought\s+(?:about|of)|буду|завтра|планир\w*|"
    r"намерева\w*|введу|уколю|подколю|поставлю|"
    r"следующ\w+\s+(?:день|недел\w*|месяц\w*|год\w*))\b|"
    r"\b(?:собира(?:юсь|ешься|ется|емся|етесь|ются|лся|лась|лись)|"
    r"хотел\w*|думал\w*|"
    r"(?:долж(?:ен|на|ны)|надо|нужно)\s+(?:был|была|были|было))\b"
    r"(?=.{0,48}\b(?:inject|take|administer|уколоть|ввести|подколоть|"
    r"поставить)\b)|\b(?:later|позже)\s*[.!]*$",
    re.IGNORECASE,
)
_NEGATION = re.compile(
    r"\b(?:not|never|no\s+longer|didn['’]?t|don['’]?t|doesn['’]?t|"
    r"wasn['’]?t|weren['’]?t|haven['’]?t|hasn['’]?t|hadn['’]?t|"
    r"won['’]?t|не|никогда)\b",
    re.IGNORECASE,
)
_CONDITIONAL = re.compile(
    r"\b(?:if|unless|would|could|might|maybe|perhaps|hypothetical(?:ly)?|"
    r"если|если\s+бы|может\s+быть|возможно|предположим)\b",
    re.IGNORECASE,
)
_UNCERTAINTY = re.compile(
    r"\b(?:or\s+did\s+i|not\s+sure|unsure|i\s+(?:am|'m|’m)\s+not\s+sure|"
    r"i\s+(?:think|guess)|possibly|perhaps|maybe|"
    r"или\s+нет|не\s+уверен(?:а)?|вроде|кажется|возможно|может\s+быть)\b",
    re.IGNORECASE,
)
_QUOTED_OR_LABEL = re.compile(
    r"[\"“”«»]|\b(?:label|package|box|pen|screen|photo)\s+(?:says?|said|reads?)\b|"
    r"\b(?:says?|said|quote|quoted)\b|"
    r"(?:этикетк\w*|упаковк\w*|коробк\w*|написан\w*|сказал\w*|цитат\w*)",
    re.IGNORECASE,
)
_RECOMMENDATION = re.compile(
    r"\b(?:recommend\w*|advi[cs]e\w*|suggest\w*|should|need\s+to\s+take|"
    r"how\s+much|сколько|посовет\w*|рекоменд\w*|подскажи\w*|"
    r"нужно\s+(?:ввести|уколоть|подколоть))\b",
    re.IGNORECASE,
)
_PROMPT_INJECTION = re.compile(
    r"\b(?:ignore|disregard|override|forget|bypass|safeguards?|jailbreak|"
    r"role[-\s]?play|previous\s+(?:rules?|instructions?|prompts?)|"
    r"(?:system|developer|assistant)(?=\s*:)|"
    r"follow\s+(?:my\s+)?(?:new\s+)?(?:rules?|instructions?)|"
    r"(?:new|trusted)\s+(?:rules?|instructions?|context)|"
    r"treat\s+(?:this|it|the\s+following)\s+as\s+"
    r"(?:trusted|system|developer|instructions?)|"
    r"act\s+as\s+(?:a\s+|an\s+|the\s+)?(?:developer|system|assistant)|"
    r"(?:output|record|log|create|apply)\s+(?:a\s+|an\s+|the\s+|this\s+|that\s+)?"
    r"(?:meal|entry|record|json|cake|pizza)|"
    r"instruction\w*|system\s+prompt|developer\s+message|"
    r"assistant|json\s+schema|игнорир\w*|инструкц\w*|системн\w+\s+промпт\w*|"
    r"промпт\w*|сообщени\w+\s+разработчик\w*|обойди\w*\s+защит\w*|"
    r"(?:систем\w*|разработчик\w*|ассистент\w*)(?=\s*:)|"
    r"следуй\w*\s+(?:моим\s+)?(?:новым\s+)?правил\w*|"
    r"считай\w*\s+(?:это|следующее)\s+(?:доверенн\w*|системн\w*)|"
    r"действуй\w*\s+как\s+(?:разработчик\w*|систем\w*|ассистент\w*)|"
    r"(?:выведи|запиши|создай|примени)\w*\s+(?:ед\w*|запис\w*|торт\w*|пицц\w*))\b",
    re.IGNORECASE,
)
_RANGE_OR_ALTERNATIVE = re.compile(
    rf"{_NUMBER}\s*(?:[-–—/]|\b(?:or|to|through|или|либо|до)\b)\s*{_NUMBER}|"
    rf"\b(?:between|from|от)\s+{_NUMBER}.{{0,12}}{_NUMBER}",
    re.IGNORECASE,
)
_NON_INSULIN_UNIT_BODY = (
    r"(?:mg|ml|g|kg|mcg|cc|millilit(?:er|re)s?|milligrams?|micrograms?|"
    r"kilograms?|grams?|carbs?|apples?|мг|мл|г|кг|миллилитр\w*|"
    r"миллиграмм\w*|микрограмм\w*|килограмм\w*|грамм\w*|кубик\w*|"
    r"углевод\w*)"
)
_WRONG_UNIT_NEXT_TO_NUMBER = re.compile(
    rf"(?:"
    rf"(?<![\d.,\w]){_SEMANTIC_DOSE_TOKEN_BODY}{_SEMANTIC_DOSE_CONTEXT_END}\s*"
    rf"{_NON_INSULIN_UNIT_BODY}\b|"
    rf"\b{_NON_INSULIN_UNIT_BODY}\s*"
    rf"{_SEMANTIC_DOSE_TOKEN_BODY}{_SEMANTIC_DOSE_CONTEXT_END}"
    rf")",
    re.IGNORECASE,
)
_GLUCOSE_VALUE_CONTEXT = re.compile(
    rf"(?:\b(?:glucose|blood\s+sugar|sugar|сахар|глюкоз\w*)\b"
    rf"(?:\s+(?:is|was|at|был\w*|равен\w*))?\s*"
    rf"{_SEMANTIC_DOSE_TOKEN_BODY}{_SEMANTIC_DOSE_CONTEXT_END}|"
    rf"(?<![\d.,\w]){_SEMANTIC_DOSE_TOKEN_BODY}{_SEMANTIC_DOSE_CONTEXT_END}\s*"
    rf"(?:mmol(?:/l)?|mg/dl|ммоль(?:/л)?|мг/дл)\b)",
    re.IGNORECASE,
)
_TIME_VALUE_CONTEXT = re.compile(
    rf"(?:"
    rf"(?<![\d.,\w]){_SEMANTIC_DOSE_TOKEN_BODY}{_SEMANTIC_DOSE_CONTEXT_END}\s*"
    rf"(?:minutes?|mins?|hours?|hrs?|минут\w*|час\w*)\s*"
    rf"(?:ago|назад)?\b|"
    rf"\b(?:at|в)\s+{_SEMANTIC_DOSE_TOKEN_BODY}{_SEMANTIC_DOSE_CONTEXT_END}\s*"
    rf"(?:o['’]?clock|a\.?m\.?|p\.?m\.?|утра|дня|вечера|ночи|час\w*)\b"
    rf")",
    re.IGNORECASE,
)

_REPLACE_HINT = re.compile(
    r"\b(?:actually|correction|correct(?:ed)?|replace|change|fix|instead|"
    r"исправ\w*|замен\w*|поправ\w*|вместо)\b|(?:^|[,;]\s*)(?:no|нет)\b|"
    r"\b(?:not\s+.+?\s+but|не\s+.+?\s+а)\s+",
    re.IGNORECASE | re.DOTALL,
)
_ENGLISH_EXPLICIT_CORRECTION = re.compile(
    r"\bnot\b(?P<old>.+?)\bbut\b(?P<new>.+)$",
    re.IGNORECASE | re.DOTALL,
)
_RUSSIAN_EXPLICIT_CORRECTION = re.compile(
    r"\bне\b(?P<old>.+?)\bа\b(?P<new>.+)$",
    re.IGNORECASE | re.DOTALL,
)
_DOSE_ONLY = re.compile(
    rf"\s*(?P<units>{_NUMBER})(?:\s*{_UNIT})?\s*[,;:.!?]*\s*",
    re.IGNORECASE,
)
_RU_SPOKEN_DOSE_ONLY = re.compile(
    rf"\s*(?P<number>{_RU_SPOKEN_NUMBER})(?:\s*{_UNIT})?\s*",
    re.IGNORECASE,
)
_DOSE_CORRECTION_PREFIX = re.compile(
    r"^\s*(?:actually|correction|corrected|more\s+precisely|"
    r"точнее|вернее)\b[\s,;:\-–—]*",
    re.IGNORECASE,
)
_DOSE_CORRECTION_SUFFIX = re.compile(
    r"[\s,;:\-–—]*(?:to\s+be\s+precise|more\s+precisely|"
    r"точнее|вернее)\s*$",
    re.IGNORECASE,
)
_CONTEXTUAL_NEW_INSULIN_DOSE = (
    re.compile(
        rf"\s*(?:another|an\s+additional)\s+"
        rf"(?P<dose>{_EXPLICIT_DOSE_TOKEN})\s*[.!]*\s*",
        re.IGNORECASE,
    ),
    re.compile(
        rf"\s*(?P<dose>{_EXPLICIT_DOSE_TOKEN})\s+more\s*[.!]*\s*",
        re.IGNORECASE,
    ),
    re.compile(
        rf"\s*(?:(?:i|we)\s+)?(?:(?:have|had|['’]ve)\s+)?"
        rf"(?:(?:just|now)\s+)?(?:injected|took|taken|administered)\s+"
        rf"(?:(?:another|an\s+additional)\s+)?"
        rf"(?P<dose>{_EXPLICIT_DOSE_TOKEN})\s*[.!]*\s*",
        re.IGNORECASE,
    ),
    re.compile(
        rf"\s*(?:ещ[ёе]\s+)(?P<dose>{_EXPLICIT_DOSE_TOKEN})\s*[.!]*\s*",
        re.IGNORECASE,
    ),
    re.compile(
        rf"\s*(?:(?:я|мы)\s+)?(?:(?:только\s+что|сейчас)\s+)?"
        rf"(?:вв[её]л\w*|ввела|ввели|уколол\w*|подколол\w*|"
        rf"поставил\w*|сделал\w*)\s+(?:ещ[ёе]\s+)?"
        rf"(?P<dose>{_EXPLICIT_DOSE_TOKEN})\s*[.!]*\s*",
        re.IGNORECASE,
    ),
)
_EXPLICIT_NEW_INSULIN_MARKER = re.compile(
    r"\b(?:another|additional|again|injected|took|taken|administered|"
    r"ещ[ёе]|вв[её]л\w*|ввела|ввели|уколол\w*|подколол\w*|"
    r"поставил\w*|сделал\w*|вкатил\w*)\b",
    re.IGNORECASE,
)
_EXPLICIT_TRAILING_MORE_INSULIN = re.compile(
    rf"(?<![\w]){_EXPLICIT_DOSE_TOKEN}\s+more\b",
    re.IGNORECASE,
)
_CORRECTION_PREFIX = re.compile(
    r"\s*(?:(?:no|нет|actually|correction|corrected|исправление)"
    r"\b[\s,;:\-–—]*)*",
    re.IGNORECASE,
)
_CONTEXTUAL_DOSE_CORRECTIONS = (
    re.compile(
        rf"\s*(?:(?:no|actually|correction|corrected)\b[\s,;:\-–—]*)*"
        rf"not\s+(?P<old>{_NUMBER})(?:\s*{_UNIT})?\s*"
        rf"(?:,\s*(?:but\s+)?|but\s+)"
        rf"(?P<new>{_NUMBER})(?:\s*{_UNIT})?\s*[.!?]*\s*",
        re.IGNORECASE,
    ),
    re.compile(
        rf"\s*(?:(?:нет|исправление|поправка)\b[\s,;:\-–—]*)*"
        rf"не\s+(?P<old>{_NUMBER})(?:\s*{_UNIT})?\s*"
        rf"(?:,\s*(?:а\s+)?|а\s+)"
        rf"(?P<new>{_NUMBER})(?:\s*{_UNIT})?\s*[.!?]*\s*",
        re.IGNORECASE,
    ),
)
_TERSE_INSULIN_CORRECTION_CUE = re.compile(
    r"\b(?:no|actually|correction|corrected|wrong|incorrect|mistake|"
    r"was|were|is|should\s+be|"
    r"нет|точнее|вернее|ошиб(?:ся|лась|лись|ка)|ошибочн\w*|"
    r"неправильн\w*|неверн\w*|был(?:о|а|и)?|должн\w*\s+быть)\b",
    re.IGNORECASE,
)
_TERSE_INSULIN_STRONG_CORRECTION_CUE = re.compile(
    r"\b(?:no|actually|correction|corrected|wrong|incorrect|mistake|"
    r"нет|точнее|вернее|ошиб(?:ся|лась|лись|ка)|ошибочн\w*|"
    r"неправильн\w*|неверн\w*)\b",
    re.IGNORECASE,
)
_TERSE_INSULIN_UNIT_REFERENT = re.compile(
    rf"(?<![\d\w]){_SEMANTIC_DOSE_TOKEN_BODY}{_SEMANTIC_DOSE_CONTEXT_END}"
    rf"\s*{_UNIT}(?![\w])",
    re.IGNORECASE,
)
_TERSE_INSULIN_ALLOWED_FILLER = re.compile(
    r"\b(?:i|we|my|this|that|the|last|latest|current|dose|insulin|"
    r"units?|iu|u|no|actually|correction|corrected|wrong|incorrect|"
    r"mistake|was|were|is|should|be|there|it|"
    r"я|мы|мой|моя|мою|этот|эта|эту|это|тот|та|ту|последн\w*|"
    r"текущ\w*|доз\w*|инсулин\w*|единиц\w*|ед\.?|нет|точнее|"
    r"вернее|ошиб(?:ся|лась|лись|ка)|ошибочн\w*|неправильн\w*|"
    r"неверн\w*|был(?:о|а|и)?|должн\w*|быть|там)\b",
    re.IGNORECASE,
)
_INSULIN_TIME_CORRECTION_HINT = re.compile(
    r"\b(?:not\s+now\s+but|actually|correct|change|fix|set|"
    r"time|timestamp|this|that|last|current|"
    r"не\s+сейчас\s*,?\s*а|на\s+самом\s+деле|исправ\w*|"
    r"измен\w*|поменя\w*|врем\w*|этот|эта|эту|последн\w*|"
    r"текущ\w*)\b",
    re.IGNORECASE,
)
_INSULIN_TIME_ALLOWED_FILLER = re.compile(
    r"\b(?:i|we|my|this|that|the|last|latest|current|insulin|dose|"
    r"injection|shot|not|now|but|actually|correct|corrected|change|"
    r"changed|fix|fixed|set|time|timestamp|it|was|is|at|"
    r"я|мы|мой|моя|мою|этот|эта|эту|это|тот|та|ту|последн\w*|"
    r"текущ\w*|инсулин\w*|доз\w*|укол\w*|не|сейчас|а|"
    r"на|самом|деле|исправ\w*|измен\w*|поменя\w*|врем\w*|"
    r"был(?:о|а|и)?|есть|постав\w*)\b",
    re.IGNORECASE,
)
_PORTION_NUMBER = r"(?:10000(?:[.,]0{1,2})?|[1-9]\d{0,3}(?:[.,]\d{1,2})?|0[.,]\d{1,2})"
_GRAM_UNIT = r"(?:g|grams?|г|гр\.?|грамм(?:а|ов)?)"
_TERSE_MEAL_PORTION_VALUE = re.compile(
    rf"(?<![\d.,\w])(?P<value>{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})"
    rf"(?![\d\w]|[.,]\d)",
    re.IGNORECASE,
)
_TERSE_MEAL_PORTION_GRAMS = re.compile(
    rf"(?<![\d.,\w])(?P<value>{_PORTION_NUMBER})\s*{_GRAM_UNIT}(?![\w])",
    re.IGNORECASE,
)
_TERSE_MEAL_INSULIN_UNITS = re.compile(
    rf"(?<![\d.,\w])(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})\s*"
    rf"{_UNIT}(?![\w])",
    re.IGNORECASE,
)
_TERSE_MEAL_WRONG_PORTION_UNITS = re.compile(
    rf"(?<![\d.,\w])(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})\s*"
    r"(?:mg|ml|kg|mcg|cc|millilit(?:er|re)s?|milligrams?|micrograms?|"
    r"kilograms?|мг|мл|кг|миллилитр\w*|миллиграмм\w*|"
    r"микрограмм\w*|килограмм\w*)(?![\w])",
    re.IGNORECASE,
)
_TERSE_MEAL_CARB_MASS = re.compile(
    rf"(?<![\d.,\w])(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})\s*"
    rf"{_GRAM_UNIT}\s*(?:of\s+)?(?:carbs?|carbohydrates?|углевод\w*)(?![\w])",
    re.IGNORECASE,
)
_TERSE_MEAL_SIGNED_PORTION = re.compile(
    rf"(?<![\d\w])[+\-−]\s*(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})",
    re.IGNORECASE,
)
_TERSE_MEAL_GREETING_ONLY = re.compile(
    r"\s*(?:hi|hello|hey|thanks?|thank\s+you|ok(?:ay)?|"
    r"привет|здравствуй\w*|спасибо|ок(?:ей)?|ладно|хорошо)\s*[.!]*\s*",
    re.IGNORECASE,
)
_TERSE_MEAL_PORTION_RANGE = re.compile(
    rf"(?:"
    rf"(?<![\d.,\w])(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})"
    rf"(?![\d\w]|[.,]\d)(?:\s*{_GRAM_UNIT})?\s*"
    rf"(?:[-–—/]|\b(?:or|to|through|versus|vs\.?|или|либо|до)\b)\s*"
    rf"(?<![\d.,\w])(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})"
    rf"(?![\d\w]|[.,]\d)|"
    rf"\b(?:between|from|между|от)\b\s+"
    rf"(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER}).{{0,20}}?"
    rf"(?:{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})"
    rf")",
    re.IGNORECASE,
)
_TERSE_MEAL_PORTION_MAX_CHARS = 240
_TERSE_MEAL_PORTION_MAX_TOKENS = 32
_CONTEXTUAL_MEAL_QUANTITY_CORRECTIONS = (
    re.compile(
        rf"\s*(?:(?:no|actually|correction|corrected)\b[\s,;:\-–—]*)*"
        rf"not\s+(?P<old>{_PORTION_NUMBER})\s*{_GRAM_UNIT}\s*,?\s*"
        rf"but\s+(?P<new>{_PORTION_NUMBER})\s*{_GRAM_UNIT}\s*[.!?]*\s*",
        re.IGNORECASE,
    ),
    re.compile(
        rf"\s*(?:(?:нет|исправление|поправка)\b[\s,;:\-–—]*)*"
        rf"не\s+(?P<old>{_PORTION_NUMBER})\s*{_GRAM_UNIT}\s*,?\s*"
        rf"а\s+(?P<new>{_PORTION_NUMBER})\s*{_GRAM_UNIT}\s*[.!?]*\s*",
        re.IGNORECASE,
    ),
)
_RU_SPOKEN_BEFORE_GRAM = re.compile(
    rf"(?<![\w])(?P<number>{_RU_SPOKEN_NUMBER})"
    rf"(?P<gap>\s+)(?P<unit>{_GRAM_UNIT})(?![\w])",
    re.IGNORECASE,
)
_RELATIVE_MEAL_TIME = re.compile(
    rf"(?<![\w])(?P<number>{_PORTION_NUMBER}|{_RU_SPOKEN_NUMBER})\s+"
    r"(?P<unit>minutes?|mins?|hours?|hrs?|минут(?:у|ы)?|мин\.?|"
    r"час(?:а|ов)?)\s+(?:ago|назад)(?![\w])",
    re.IGNORECASE,
)
_PAST_TIME_MARKER = re.compile(
    r"\b(?:ago|yesterday|earlier|last\s+(?:night|morning|evening)|"
    r"назад|вчера|позавчера|ранее|раньше|"
    r"прошл(?:ой|ым)\s+(?:ночью|утром|вечером))\b|"
    r"\b(?:today\s+at|at)\s+\d{1,2}(?::\d{2}|\s*(?:am|pm))\b|"
    r"\b(?:сегодня\s+в|в)\s+\d{1,2}:\d{2}\b",
    re.IGNORECASE,
)
_UNDO_ONLY = re.compile(
    r"\s*(?:(?:окей|ладно|хорошо|okay|ok)[,;:\s]+)?(?:"
    r"undo\s+(?:that|this|it|(?:the\s+)?last\s+(?:change|action|entry|record))|"
    r"cancel\s+(?:that\s+(?:change|action)|(?:the\s+)?last\s+"
    r"(?:change|action|entry|record))|"
    r"отмени\s+(?:запис\w*|это\s+(?:изменени\w*|действи\w*)|"
    r"эту\s+запис\w*|"
    r"последн\w*\s+(?:изменени\w*|действи\w*|запис\w*))"
    r")(?:[,\s]+(?:пожалуйста|please))?\s*[.!?]*\s*",
    re.IGNORECASE,
)
_DELETE_CURRENT_ONLY = re.compile(
    r"\s*(?:(?:окей|ладно|хорошо|okay|ok)[,;:\s]+)?(?:давай\s+)?(?:(?:delete|remove)\s+"
    r"(?:that|this|it|(?:that|this)\s+(?:entry|record)|"
    r"the\s+(?:last|current)\s+(?:entry|record))|"
    r"(?:удали|убери)\s+"
    r"(?:это|эту\s+запис\w*|последн\w*\s+запис\w*))"
    r"(?:[,\s]+(?:пожалуйста|please))?\s*[.!?]*\s*",
    re.IGNORECASE,
)
_REVISION_REQUEST_ONLY = re.compile(
    r"\s*(?:(?:окей|ладно|хорошо|okay|ok)[,;:\s]+)?(?:"
    r"мне\s+(?:это|так|эта\s+запись)\s+не\s+понравил(?:ось|ась)"
    r"\s*[,;]\s*давай\s+(?:это\s+)?по[-\s]?другому|"
    r"мне\s+(?:это|так|эта\s+запись)\s+не\s+понравил(?:ось|ась)|"
    r"давай\s+(?:это\s+)?по[-\s]?другому|"
    r"(?:сделай|переделай)\s+(?:это\s+)?по[-\s]?другому|"
    r"(?:исправь|измени|переделай)\s+(?:это|эту\s+запись|"
    r"последн\w*\s+запис\w*)|"
    r"i\s+(?:do\s+not|don't|did\s+not|didn't)\s+like\s+(?:that|this|it)|"
    r"(?:let['’]?s|let\s+us)\s+(?:do\s+)?(?:this|that|it)?\s*"
    r"differently|"
    r"(?:do|make)\s+(?:this|that|it)\s+differently|"
    r"(?:fix|correct|change|revise)\s+(?:that|this|it|the\s+last\s+entry)"
    r")(?:[,\s]+(?:пожалуйста|please))?\s*[.!?]*\s*",
    re.IGNORECASE,
)
_CANCEL_PENDING_ONLY = re.compile(
    r"\s*(?:(?:окей|ладно|хорошо|okay|ok)[,;:\s]+)?(?:"
    r"cancel|never\s+mind|не\s+надо|забудь|забей|отмена"
    r")(?:[,\s]+(?:пожалуйста|please))?\s*[.!?]*\s*",
    re.IGNORECASE,
)

_MEAL_REPORT = re.compile(
    r"(?:^|[.!?;:]\s*|\b(?:and|but|plus|then|и|но|плюс|затем|потом)\s+|"
    r"\bа(?:\s+также)?\s+)"
    r"(?:(?:just|now|только\s+что|сейчас)\s+)?"
    r"(?:(?:i|we)(?:['’]ve\s+(?:(?:just|already|now)\s+)?eaten|"
    r"\s+(?:(?:just|already|now)\s+)?(?:ate|have\s+"
    r"(?:(?:just|already)\s+)?eaten|had|drank|consumed))|"
    r"(?:ate|eaten|had|drank|consumed)|"
    r"(?:я|мы)\s+(?:(?:только\s+что|сейчас|уже)\s+)?"
    r"(?:съел\w*|поел\w*|выпил\w*|съела|поела|выпила|"
    r"съели|поели|выпили)|(?:съел\w*|поел\w*|выпил\w*))\b",
    re.IGNORECASE,
)
_SEMANTIC_MEAL_CONSUMPTION_CUE = re.compile(
    r"\b(?:ate|eaten|had|drank|consumed|съел\w*|поел\w*|выпил\w*|"
    r"съела|поела|выпила|съели|поели|выпили)\b",
    re.IGNORECASE,
)
_POSTPOSED_NAMED_MEAL_ACTOR = re.compile(
    r"\b(?i:ate|had|drank|consumed|съел\w*|съела|съели|поел\w*|"
    r"поела|поели|выпил\w*|выпила|выпили)\s+(?:by\s+)?"
    r"(?:[A-ZА-ЯЁ][a-zа-яё'’\-]{1,})(?=\s+[\dA-Za-zА-Яа-яЁё])"
)
_COMMON_PERSON_NAME_PATTERN = (
    r"john|jane|alice|bob|maria|mary|michael|david|anna|alex|alexander|"
    r"иван|мария|анна|александр|алексей|сергей|дмитрий|максим|михаил|"
    r"андрей|артем|артём|никита|ольга|елена|наталья|ирина|екатерина|"
    r"петр|пётр|павел|владимир|виктор|юрий|роман|денис|евгений"
)
_COMMON_PERSON_NAME_TOKEN = re.compile(
    rf"(?:{_COMMON_PERSON_NAME_PATTERN})",
    re.IGNORECASE,
)
_POSTPOSED_COMMON_MEAL_ACTOR = re.compile(
    r"\b(?i:ate|had|drank|consumed|съел\w*|съела|съели|поел\w*|"
    rf"поела|поели|выпил\w*|выпила|выпили)\s+(?:by\s+)?(?:"
    rf"{_COMMON_PERSON_NAME_PATTERN})\b",
    re.IGNORECASE,
)
_OTHER_PERSON_MEAL_REPORT = re.compile(
    r"\b(?:(?:he|she|they|my\s+(?:child|son|daughter|friend|partner|"
    r"mother|father|neighbor|neighbour|coworker|colleague)|"
    r"neighbor|neighbour|coworker|colleague|он|она|они|"
    r"мой\s+(?:реб[её]нок|сын|друг|сосед|коллега)|"
    r"моя\s+(?:дочь|мама|подруга)|мама|папа)\s+"
    r"(?:ate|had|drank|consumed|съел\w*|съела|съели|поел\w*|"
    r"поела|поели|выпил\w*|выпила|выпили))\b",
    re.IGNORECASE,
)
_NON_SELF_ACTOR_CUE = re.compile(
    r"\b(?:you|he|she|they|him|her|them|"
    r"(?:your|our|their)\s+(?:child|son|daughter|friend|partner|"
    r"brother|sister|mother|father|parent|spouse|husband|wife)|"
    r"my\s+(?:child|son|daughter|friend|partner|mother|father|brother|"
    r"sister|parent|spouse|husband|wife)|"
    r"brother|sister|child|son|daughter|mother|father|friend|partner|"
    r"parent|spouse|husband|wife|neighbor|neighbour|coworker|colleague|"
    r"boss|doctor|physician|"
    r"ты|вы|он|она|они|ему|ей|им|"
    r"(?:(?:твой|твоя|твои|ваш|ваша|ваши|мой|моя|мои|наш|наша|наши|"
    r"его|е[её]|их)\s+)?(?:реб[её]н\w*|сын\w*|доч\w*|брат\w*|"
    r"сестр\w*|друг(?:а|у|ом|е|ья\w*)?|подруг\w*|мам\w*|пап\w*|"
    r"матер\w*|отц\w*|бабушк\w*|сосед\w*|коллег\w*|знаком\w*|"
    r"врач\w*|доктор\w*|начальник\w*|"
    r"дедушк\w*|муж\w*|жен\w*|партн[её]р\w*))\b",
    re.IGNORECASE,
)
_BARE_TREATMENT_QUESTION = re.compile(
    r"^\s*(?:(?:мне|нам)\s+(?:уколоть|ввести|подколоть|поставить)|"
    r"(?:should|could|can|may)\s+(?:i|we)\s+(?:inject|take))\b",
    re.IGNORECASE,
)
_NEGATED_MUTATION_CONTROL = re.compile(
    r"\b(?:(?:do\s+not|don['’]?t|not)\s+"
    r"(?:delete|remove|change|replace|revise|edit|fix|record|log|save|add)|"
    r"не\s+(?:удаляй|удалить|убирай|убрать|исправляй|исправить|"
    r"меняй|изменяй|заменяй|переделывай|трогай|записывай|записать|"
    r"сохраняй|сохранить|добавляй|добавить))\b",
    re.IGNORECASE,
)
_NEGATED_INSULIN_ACTION = re.compile(
    r"\b(?:(?:did\s+not|didn['’]?t|do\s+not|don['’]?t|never|not)\s+"
    r"(?:inject\w*|administer\w*|take|took|dose\w*)|"
    r"(?:no|without)\s+(?:insulin|injection|dose)|"
    r"не\s+(?:вв[её]л\w*|ввела|ввели|вводил\w*|уколол\w*|"
    r"подколол\w*|колол\w*|поставил\w*|сделал\w*)|"
    r"без\s+(?:инсулин\w*|укол\w*|доз\w*))\b",
    re.IGNORECASE,
)
_COMPLETED_INSULIN_ACTION = re.compile(
    r"\b(?:injected|administered|took|taken|bolused|dosed|"
    r"вв[её]л\w*|ввела|ввели|вводил\w*|уколол\w*|подколол\w*|"
    r"колол\w*|поставил\w*|сделал\w*|вкатил\w*|болюснул\w*|"
    r"дозировал\w*)\b",
    re.IGNORECASE,
)
_SELF_OR_SUBJECTLESS_ACTION_PREFIX = re.compile(
    r"\s*(?:(?:i|we|я|мы|ve|have|has|had|did|just|now|already|only|"
    r"myself|ourselves|well|then|today|"
    r"только|что|сейчас|уже|сам|сама|себе|ну|вот|потом|сегодня|"
    r"по|ошибке|случайно|accidentally|okay|ok|ок(?:ей)?)"
    r"\b[\s,'’.:()\[\]{}+\-–—/]*)*",
    re.IGNORECASE,
)
_SELF_OR_SUBJECTLESS_ACTION_SUFFIX = re.compile(
    r"\s*(?:(?:by\s+(?:me|myself|us|ourselves)|me|myself|us|ourselves|"
    r"by\s+(?:mistake|accident)|себе|сам|сама|сами|just|now|today|"
    r"yesterday|earlier|accidentally|сейчас|сегодня|вчера|ранее|раньше|"
    r"случайно|по\s+ошибке)\b[\s,'’.:()\[\]{}+\-–—/]*)*",
    re.IGNORECASE,
)
_POSTPOSED_BY_NON_SELF_ACTOR = re.compile(
    r"\bby\s+(?!(?:me|myself|us|ourselves|mistake|accident)\b)"
    r"[A-Za-zА-Яа-яЁё][A-Za-zА-Яа-яЁё'’\-]*\b",
    re.IGNORECASE,
)
_SEMANTIC_ACTOR_SELF_WORDS = {
    "i", "we", "me", "myself", "us", "ourselves",
    "я", "мы", "мне", "нам", "себе", "сам", "сама", "сами",
}
_SEMANTIC_ACTOR_FILLER_WORDS = {
    "a", "an", "the", "by", "have", "has", "had", "did", "just",
    "now", "already", "only", "well", "then", "today", "yesterday",
    "earlier", "actually", "correction", "corrected", "fix", "change",
    "replace", "last", "entry", "record", "to", "more", "precisely",
    "ve", "а", "в", "на", "по", "и", "ну", "вот", "только", "что",
    "сейчас", "уже", "потом", "сегодня", "вчера", "ранее", "раньше",
    "нет", "точнее", "вернее", "исправь", "исправить", "измени",
    "изменить", "замени", "заменить", "последнюю", "последней",
    "запись", "записи", "дозу", "там", "было", "должно", "быть",
    "это", "неверно", "неправильно", "ошибка", "wrong", "incorrect",
    "смотри", "слушай", "please", "пожалуйста", "my", "мой", "моя",
    "mistake", "accident", "accidentally", "ошибке", "случайно",
}
_SEMANTIC_ALTERNATIVE_CUE = re.compile(
    r"\b(?:or|или|либо)\b",
    re.IGNORECASE,
)
_SEMANTIC_CLAUSE_SEPARATOR = re.compile(
    r"\s*(?:;|\b(?:and|but|plus|then|и|но|плюс|затем|потом)\b|"
    r"\bа(?:\s+также)?\b)\s*",
    re.IGNORECASE,
)
_TRAILING_INTERROGATIVE_OR_REQUEST = re.compile(
    r"(?:,\s*|\b(?:and|but|then|or|и|а|но|затем)\s+)"
    r"(?:who|what|how|where|when|why|which|whose|whom|"
    r"(?:do|does|did|can|could|would|should|will|has|have|had|may|might|"
    r"must|shall)\s+"
    r"(?:i|you|we|they|this|that|it|there)|"
    r"(?:am|is|are|was|were)\s+(?:i|you|we|they|this|that|it|there)|"
    r"(?:i|we)(?:['’]d|\s+would)\s+like\s+(?:to\s+know|information|details)\b|"
    r"(?:i|we)\s+(?:need|want)\s+(?:information|details|to\s+know)\b|"
    r"(?:i|we)\s+wonder(?:ing)?\b|"
    r"(?:i|we|you|he|she|they|it|this|that|я|мы|ты|вы|он|она|они|это)\s+\S+|"
    r"please\b|(?:identify|tell|calculate|estimate|analy[sz]e)\b|"
    r"кто|что|сколько|как|где|когда|почему|зачем|"
    r"(?:можно|стоит|нужно)(?:\s+ли)?\b|это\s+ли\b|ли\b|пожалуйста\b|"
    r"(?:подскажи|скажи|определи|посчитай|оцени|проанализируй)\w*\b)",
    re.IGNORECASE,
)
_ENGLISH_MEAL_CORRECTION = re.compile(
    r"\s*(?:correction|corrected|actually)\s*[:,\-–—]?\s*"
    r"(?:(?:i|we)\s+)?(?:ate|have\s+eaten|had|drank|consumed)\b.+",
    re.IGNORECASE | re.DOTALL,
)
_RUSSIAN_MEAL_CORRECTION = re.compile(
    r"\s*(?:исправление|поправка|на\s+самом\s+деле)\s*[:,\-–—]?\s*"
    r"(?:(?:я|мы)\s+)?(?:съел\w*|поел\w*|выпил\w*|съела|поела|"
    r"выпила|съели|поели|выпили)\b.+",
    re.IGNORECASE | re.DOTALL,
)
_ADDITIONAL_MEAL_MARKER = re.compile(
    r"\b(?:another|additional|one\s+more|again|also|"
    r"ещ[ёе]|дополнительно|снова|также)\b",
    re.IGNORECASE,
)
_INJECTION_WRAPPER = re.compile(
    r"\b(?:i|я|we|мы|ve|just|только|что|now|сейчас|have|has|had|did|"
    r"took|taken|inject(?:ed|ing)?|administered|shot|"
    r"ввел\w*|ввёл\w*|ввела|вводил\w*|ввожу|уколол\w*|"
    r"подколол\w*|колол\w*|колю|поставил\w*|ставлю|сделал\w*|себе)\b",
    re.IGNORECASE,
)
_ALLOWED_CONNECTOR = re.compile(
    r"\b(?:i|я|we|мы|ve|just|только|что|now|сейчас|have|has|had|did|"
    r"took|taken|take|inject|injected|injecting|administered|"
    r"ввел\w*|ввёл\w*|ввела|вводил\w*|ввожу|уколол\w*|"
    r"подколол\w*|колол\w*|колю|поставил\w*|ставлю|сделал\w*|себе|"
    r"and|plus|another|additional|more|again|и|плюс|ещ[ёе]|"
    r"actually|correction|corrected|нет|исправ\w*)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True, slots=True)
class ExplicitInsulinCommand:
    insulin_units: float
    insulin_name: str
    insulin_type: str
    span: tuple[int, int]


@dataclass(frozen=True, slots=True)
class ExplicitInsulinParse:
    commands: tuple[ExplicitInsulinCommand, ...]
    ambiguous: bool
    replace_requested: bool
    meal_evidence: str
    # Narrower than replace_requested: true only when deterministic text parsing
    # proves that the correction itself targets an insulin fact.
    insulin_replace_requested: bool = False
    # For an explicit product switch, this is the product kind being replaced,
    # which can differ from the replacement command's kind.
    insulin_replace_target_type: str | None = None
    # The user-stated OLD dose.  The orchestrator must compare it with the
    # session-owned target before applying any replacement.
    insulin_replace_expected_units: float | None = None


@dataclass(frozen=True, slots=True)
class ContextualInsulinDoseCorrection:
    expected_units: float
    replacement_units: float


@dataclass(frozen=True, slots=True)
class TerseInsulinDoseReplacement:
    """One bounded replacement dose and an optional explicit product target."""

    replacement_units: float
    insulin_name: str | None
    insulin_type: str | None
    has_explicit_referent: bool


@dataclass(frozen=True, slots=True)
class ContextualInsulinTimeCorrection:
    """A relative-past timestamp replacement and optional product target."""

    offset_ms: int
    insulin_name: str | None
    insulin_type: str | None


@dataclass(frozen=True, slots=True)
class ContextualMealQuantityCorrection:
    expected_grams: float
    replacement_grams: float


@dataclass(frozen=True, slots=True)
class IncompleteInsulinProduct:
    """A safely recognized product whose exact dose is still missing."""

    insulin_name: str
    insulin_type: str


def _canonical_product(raw: str) -> tuple[str, str]:
    lowered = raw.casefold()
    if (
        "tres" in lowered
        or "трес" in lowered
        or "slow" in lowered
        or "медленн" in lowered
    ):
        return "Tresiba", "long"
    return "NovoRapid", "rapid"


def _format_spoken_number(raw: str) -> str:
    value = _RU_SPOKEN_NUMBERS[raw.casefold()]
    return f"{value:g}"


def _normalize_spoken_dose_numbers(text: str) -> str:
    """Normalize Russian number words only in an insulin-like dose position."""

    normalized = _RU_SPOKEN_WITH_UNIT.sub(
        lambda match: _format_spoken_number(match.group("number"))
        + match.group("unit"),
        text,
    )
    normalized = _RU_SPOKEN_BEFORE_PRODUCT.sub(
        lambda match: _format_spoken_number(match.group("number"))
        + match.group("gap")
        + match.group("product"),
        normalized,
    )
    return _RU_PRODUCT_BEFORE_SPOKEN.sub(
        lambda match: match.group("product")
        + match.group("gap")
        + _format_spoken_number(match.group("number")),
        normalized,
    )


def _normalize_rapid_asr_aliases(text: str) -> str:
    """Normalize a finite ASR alias only in an explicit product+dose clause."""

    normalized = _DOSE_BEFORE_RAPID_ASR_ALIAS.sub(
        lambda match: match.group("dose")
        + match.group("gap")
        + "новорапида",
        text,
    )
    return _RAPID_ASR_ALIAS_BEFORE_DOSE.sub(
        lambda match: "новорапида"
        + match.group("gap")
        + match.group("dose"),
        normalized,
    )


def _normalize_spoken_meal_grams(text: str) -> str:
    return _RU_SPOKEN_BEFORE_GRAM.sub(
        lambda match: _format_spoken_number(match.group("number"))
        + match.group("gap")
        + match.group("unit"),
        text,
    )


def _overlaps(left: tuple[int, int], right: tuple[int, int]) -> bool:
    return left[0] < right[1] and right[0] < left[1]


def _has_insulin_hint(text: str) -> bool:
    return bool(
        _KNOWN_PRODUCT.search(text)
        or _INSULIN_LIKE_TOKEN.search(text)
        or _GENERIC_INSULIN.search(text)
        or _UNATTACHED_DOSE.search(text)
    )


def _has_unmatched_product(text: str) -> bool:
    return bool(
        _KNOWN_PRODUCT.search(text)
        or _INSULIN_LIKE_TOKEN.search(text)
        or _GENERIC_PRODUCT.search(text)
    )


def _unsafe_context(text: str, *, allow_correction_negation: bool) -> bool:
    if (
        _QUESTION.search(text)
        or _INFO_REQUEST.search(text)
        or _FUTURE_OR_PLAN.search(text)
        or _CONDITIONAL.search(text)
        or _UNCERTAINTY.search(text)
        or _QUOTED_OR_LABEL.search(text)
        or _RECOMMENDATION.search(text)
        or _PROMPT_INJECTION.search(text)
        or _RANGE_OR_ALTERNATIVE.search(text)
        or _wrong_unit_near_product(text)
    ):
        return True
    if _NEGATION.search(text) and not allow_correction_negation:
        return True
    return False


def _wrong_unit_near_product(text: str) -> bool:
    products = list(_KNOWN_PRODUCT.finditer(text))
    for wrong_unit in _WRONG_UNIT_NEXT_TO_NUMBER.finditer(text):
        for product in products:
            gap = max(
                product.start() - wrong_unit.end(),
                wrong_unit.start() - product.end(),
                0,
            )
            if gap <= 12:
                return True
    return False


def _command_matches(text: str) -> list[ExplicitInsulinCommand]:
    matches: list[ExplicitInsulinCommand] = []
    for pattern in (_DOSE_BEFORE_PRODUCT, _PRODUCT_BEFORE_DOSE):
        for match in pattern.finditer(text):
            span = match.span()
            if any(_overlaps(span, existing.span) for existing in matches):
                continue
            units = float(match.group("units").replace(",", "."))
            name, insulin_type = _canonical_product(match.group("product"))
            matches.append(
                ExplicitInsulinCommand(units, name, insulin_type, span)
            )
    matches.sort(key=lambda command: command.span)
    return matches


def _parse_units(raw: str) -> float:
    return float(raw.replace(",", "."))


def _trim_correction_fragment(text: str) -> str:
    return re.sub(
        r"^[\s,;:.!?–—-]+|[\s,;:.!?–—-]+$",
        "",
        text,
    )


def _exact_correction_command(text: str) -> ExplicitInsulinCommand | None:
    """Return one fully specified command from one side of a correction."""

    fragment = _trim_correction_fragment(text)
    matches = _command_matches(fragment)
    if len(matches) != 1:
        return None
    command = matches[0]
    residual = _without_spans(fragment, [command.span])
    if _has_unmatched_product(residual) or not _allowed_command_context(residual):
        return None
    return command


def _exact_correction_dose(text: str) -> float | None:
    if "?" in text:
        return None
    fragment = _trim_correction_fragment(text)
    fragment = _DOSE_CORRECTION_PREFIX.sub("", fragment, count=1)
    fragment = _DOSE_CORRECTION_SUFFIX.sub("", fragment, count=1)
    fragment = _trim_correction_fragment(fragment)

    match = _DOSE_ONLY.fullmatch(fragment)
    if match is not None:
        units = _parse_units(match.group("units"))
    else:
        spoken = _RU_SPOKEN_DOSE_ONLY.fullmatch(fragment)
        if spoken is None:
            return None
        units = _RU_SPOKEN_NUMBERS.get(spoken.group("number").casefold())
        if units is None:
            return None
    if units <= 0 or units > 500:
        return None
    return units


def _parse_product_correction(
    text: str,
) -> tuple[ExplicitInsulinCommand, str, float] | None:
    """Parse an exact not-OLD-but-NEW insulin correction.

    The replacement product may be inherited only when OLD is itself a complete
    product+dose command and NEW is only a bounded dose.  This intentionally does
    not provide general conversational or session-context inference.
    """

    for pattern in (
        _ENGLISH_EXPLICIT_CORRECTION,
        _RUSSIAN_EXPLICIT_CORRECTION,
    ):
        match = pattern.search(text)
        if match is None or _CORRECTION_PREFIX.fullmatch(text[: match.start()]) is None:
            continue

        old_command = _exact_correction_command(match.group("old"))
        old_dose = _exact_correction_dose(match.group("old"))
        new_command = _exact_correction_command(match.group("new"))
        new_dose = _exact_correction_dose(match.group("new"))

        # Both sides must carry a concrete dose.  NEW wins, and an explicit NEW
        # product always wins over OLD (for example Tresiba -> NovoRapid).
        if old_command is None and old_dose is None:
            return None
        if new_command is not None:
            if not 0 < new_command.insulin_units <= 500:
                return None
            target_type = (
                old_command.insulin_type
                if old_command is not None
                else new_command.insulin_type
            )
            expected_units = (
                old_command.insulin_units
                if old_command is not None
                else old_dose
            )
            if expected_units is None:
                return None
            return new_command, target_type, expected_units
        if old_command is not None and new_dose is not None:
            return (
                ExplicitInsulinCommand(
                    new_dose,
                    old_command.insulin_name,
                    old_command.insulin_type,
                    match.span("new"),
                ),
                old_command.insulin_type,
                old_command.insulin_units,
            )
        return None
    return None


def _without_spans(text: str, spans: list[tuple[int, int]]) -> str:
    characters = list(text)
    for start, end in spans:
        for index in range(max(0, start), min(len(characters), end)):
            characters[index] = " "
    return "".join(characters)


def _plain_words(text: str) -> list[str]:
    return re.findall(r"[A-Za-zА-Яа-яЁё’']+", text)


def _allowed_command_context(text: str) -> bool:
    residual = _ALLOWED_CONNECTOR.sub(" ", text)
    # Never discard an unbound number after selecting a product+dose span.
    # It may be a conflicting dose (``5 NovoRapid, 7``), and silently choosing
    # the first value would create an unsafe health record.  Exact duplicated
    # STT values are handled by the evidence-validated semantic path instead.
    residual = re.sub(r"[\s.,;:!'’()\[\]{}+\-–—/]+", " ", residual)
    if residual.strip():
        return False

    lowered = text.casefold()
    # Bare English present-tense imperatives are instructions, not reports.
    if re.search(r"\b(?:take|inject)\b", lowered) and not re.search(
        r"\b(?:i|we)\b", lowered
    ):
        return False
    return True


def _meal_only_residual(text: str) -> str:
    candidate = re.sub(
        r"^[\s,;:+\-–—]+|[\s,;:+\-–—]+$",
        "",
        text,
    )
    cleaned = _INJECTION_WRAPPER.sub(" ", candidate)
    cleaned = re.sub(
        r"^\s*\b(?:and|but|plus|then|и|но|плюс|затем|потом|"
        r"а(?:\s+также)?)\b|"
        r"\b(?:and|but|plus|then|и|но|плюс|затем|потом|"
        r"а(?:\s+также)?)\b\s*$",
        " ",
        cleaned,
        flags=re.I,
    )
    cleaned = re.sub(r"^[\s,;:+\-–—]+|[\s,;:+\-–—]+$", "", cleaned)
    cleaned = " ".join(cleaned.split())
    return cleaned if _MEAL_REPORT.search(cleaned) else ""


def _semantic_insulin_clause_bounds(
    text: str,
    insulin_span: tuple[int, int] | None,
) -> tuple[str, int, int] | None:
    """Resolve the clause containing a trusted dose/product anchor span."""

    clean = " ".join((text or "").strip().split())
    if insulin_span is None:
        return clean, 0, len(clean)
    start, end = insulin_span
    if start < 0 or end <= start or end > len(clean):
        return None
    clause_start = 0
    clause_end = len(clean)
    separators = list(_SEMANTIC_CLAUSE_SEPARATOR.finditer(clean))
    semantic_commas = list(re.finditer(r"(?<!\d),(?!\d)", clean))
    # Product and dose must originate in one clause.  A provider cannot join a
    # product from one side of a conjunction with a quantity from another.
    if any(
        separator.start() < end and separator.end() > start
        for separator in separators
    ):
        return None
    for separator in separators:
        if separator.end() <= start:
            clause_start = separator.end()
        elif separator.start() >= end:
            clause_end = separator.start()
            break

    # Speech recognition often emits two completed reports separated only by
    # a comma.  Treat a non-numeric comma as a boundary only when the opposite
    # side independently contains a safe, explicit meal report.  This avoids
    # splitting decimal quantities or arbitrary comma-delimited prose.
    for comma in semantic_commas:
        if comma.start() < clause_start or comma.end() > clause_end:
            continue
        if comma.end() <= start:
            if has_explicit_meal_consumption(clean[: comma.start()]):
                clause_start = comma.end()
        elif comma.start() >= end:
            if has_explicit_meal_consumption(clean[comma.end() :]):
                clause_end = comma.start()
                break
    return clean, clause_start, clause_end


def semantic_product_dose_evidence_is_bound(
    text: str,
    *,
    product_span: tuple[int, int],
    dose_span: tuple[int, int],
) -> bool:
    """Require one product and dose to be adjacent facts in the same clause."""

    clean = " ".join((text or "").strip().split())
    product_start, product_end = product_span
    dose_start, dose_end = dose_span
    if (
        product_start < 0
        or product_end <= product_start
        or dose_start < 0
        or dose_end <= dose_start
        or product_end > len(clean)
        or dose_end > len(clean)
        or not (product_end <= dose_start or dose_end <= product_start)
    ):
        return False
    envelope = (
        min(product_start, dose_start),
        max(product_end, dose_end),
    )
    if _semantic_insulin_clause_bounds(clean, envelope) is None:
        return False
    gap = (
        clean[product_end:dose_start]
        if product_end <= dose_start
        else clean[dose_end:product_start]
    )
    gap = re.sub(rf"(?<![\w]){_UNIT}(?![\w])", " ", gap, flags=re.IGNORECASE)
    gap = re.sub(r"[\s,.:;()\[\]{}+\-–—/]+", "", gap)
    return not gap


def semantic_action_evidence_matches_anchored_clause(
    text: str,
    *,
    anchor_span: tuple[int, int],
    action_span: tuple[int, int],
) -> bool:
    """Require action evidence to overlap the product/dose-anchored clause.

    A provider must quote the action from the same clause.  Broad evidence
    crossing into a meal/plan/other-person clause is not authorization for a
    write.  Resolving the anchor also rejects product/dose evidence assembled
    across clause separators.
    """

    resolved = _semantic_insulin_clause_bounds(text, anchor_span)
    if resolved is None:
        return False
    clean, clause_start, clause_end = resolved
    action_start, action_end = action_span
    if (
        action_start < 0
        or action_end <= action_start
        or action_end > len(clean)
    ):
        return False
    return action_start >= clause_start and action_end <= clause_end


def semantic_meal_residual(
    text: str,
    insulin_span: tuple[int, int],
) -> str:
    """Return meal evidence outside the dose/product-anchored insulin clause.

    Provider action evidence is intentionally not used as the redaction span:
    a model may quote an entire mixed meal-and-insulin turn as the action.  The
    caller supplies the exact dose/product envelope, which is expanded only to
    deterministic conjunction/semicolon clause boundaries.
    """

    resolved = _semantic_insulin_clause_bounds(text, insulin_span)
    if resolved is None:
        return ""
    clean, clause_start, clause_end = resolved
    return _meal_only_residual(
        _without_spans(clean, [(clause_start, clause_end)])
    )


def _semantic_insulin_clause(
    text: str,
    insulin_span: tuple[int, int] | None,
) -> str:
    resolved = _semantic_insulin_clause_bounds(text, insulin_span)
    if resolved is None:
        return ""
    clean, clause_start, clause_end = resolved
    return clean[clause_start:clause_end].strip()


def _has_single_meal_report_clause(
    raw_text: str,
    clean: str,
    *,
    anchored_correction: bool,
) -> bool:
    """Require one complete report, not merely a safe-looking prefix."""

    clause_scope = clean
    if anchored_correction:
        clause_scope = re.sub(
            r"^\s*(?:correction|corrected|actually|исправление|поправка|"
            r"на\s+самом\s+деле)\s*[:,\-–—]?\s*",
            "",
            clause_scope,
            count=1,
            flags=re.IGNORECASE,
        )
    if (
        "\n" in raw_text
        or "\r" in raw_text
        or ";" in clause_scope
        or ":" in clause_scope
        or "?" in clause_scope
        or re.search(r"\s(?:--|[–—])\s", clause_scope)
    ):
        return False
    # Reject another sentence even when an attacker omits whitespace.  Decimal
    # points remain valid meal quantities (for example 4.5 g).
    if re.search(r"(?<!\d)[.!](?!\d)\s*\S", clean):
        return False
    report = _MEAL_REPORT.match(clause_scope)
    if report is None:
        return False
    raw_tail = clause_scope[report.end() :]
    if _TRAILING_INTERROGATIVE_OR_REQUEST.search(raw_tail):
        return False
    tail = re.sub(r"[.!]+$", "", raw_tail).strip()
    # A consumption verb without any stated food evidence lets a model invent
    # the entire record and is therefore not sufficient authorization.
    return bool(re.search(r"[\w\u0400-\u04ff]", tail))


def has_safe_meal_consumption_candidate(text: str) -> bool:
    """Recognize one completed-meal-shaped clause before semantic actor proof.

    Food names and person names are both open vocabularies, so a local token
    pattern cannot reliably distinguish ``ate george pizza`` from ``ate chicken
    breast``.  This gate rejects every other deterministic unsafe shape while
    deliberately leaving actor resolution to the strict semantic contract.
    """

    raw_text = text or ""
    clean = " ".join(raw_text.strip().split())
    if not clean:
        return False
    anchored_correction = bool(
        _ENGLISH_MEAL_CORRECTION.fullmatch(clean)
        or _RUSSIAN_MEAL_CORRECTION.fullmatch(clean)
    )
    if not _has_single_meal_report_clause(
        raw_text,
        clean,
        anchored_correction=anchored_correction,
    ):
        return False
    if (
        _QUESTION.search(clean)
        or _INFO_REQUEST.search(clean)
        or _FUTURE_OR_PLAN.search(clean)
        or _CONDITIONAL.search(clean)
        or _UNCERTAINTY.search(clean)
        or (_NEGATION.search(clean) and not anchored_correction)
        or _QUOTED_OR_LABEL.search(clean)
        or _PROMPT_INJECTION.search(clean)
        or has_ambiguous_meal_time_reference(clean)
    ):
        return False
    return True


def has_explicit_meal_consumption(text: str) -> bool:
    """Accept a candidate unless a deterministic non-self actor is visible."""

    clean = " ".join((text or "").strip().split())
    return bool(
        has_safe_meal_consumption_candidate(clean)
        and _OTHER_PERSON_MEAL_REPORT.search(clean) is None
        and _NON_SELF_ACTOR_CUE.search(clean) is None
        and _POSTPOSED_NAMED_MEAL_ACTOR.search(clean) is None
        and _POSTPOSED_COMMON_MEAL_ACTOR.search(clean) is None
    )


def _whole_semantic_fragment_spans(
    text: str,
    fragment: str | None,
) -> tuple[tuple[int, int], ...]:
    clean = " ".join((text or "").strip().split())
    evidence = " ".join((fragment or "").strip().split())
    if not clean or not evidence:
        return ()
    return tuple(
        match.span()
        for match in re.finditer(
            r"(?<![\w'’\-])" + re.escape(evidence) + r"(?![\w'’\-])",
            clean,
            flags=re.IGNORECASE,
        )
    )


def is_safe_semantic_meal_write(
    text: str,
    *,
    event_status: str,
    actor: str,
    action_evidence: str | None,
    food_evidence: str | None,
    confidence: float,
) -> bool:
    """Require self/completed semantics and exact clause-bound meal evidence."""

    clean = " ".join((text or "").strip().split())
    if (
        event_status != "completed"
        or actor != "self"
        or confidence < 0.90
        or not has_safe_meal_consumption_candidate(clean)
    ):
        return False

    # Keep deterministic actor vetoes as defense in depth for pronouns, roles,
    # and clearly formatted names. Open-vocabulary actor resolution comes from
    # the explicit semantic field above, not from a finite local name list.
    if not has_explicit_meal_consumption(clean):
        return False

    action_spans = _whole_semantic_fragment_spans(clean, action_evidence)
    food_spans = _whole_semantic_fragment_spans(clean, food_evidence)
    if len(action_spans) != 1 or len(food_spans) != 1:
        return False

    report_scope_start = 0
    if (
        _ENGLISH_MEAL_CORRECTION.fullmatch(clean)
        or _RUSSIAN_MEAL_CORRECTION.fullmatch(clean)
    ):
        prefix = re.match(
            r"^\s*(?:correction|corrected|actually|исправление|поправка|"
            r"на\s+самом\s+деле)\s*[:,\-–—]?\s*",
            clean,
            flags=re.IGNORECASE,
        )
        report_scope_start = prefix.end() if prefix is not None else 0
    report = _MEAL_REPORT.match(clean[report_scope_start:])
    if report is None:
        return False
    report_start = report_scope_start + report.start()
    report_end = report_scope_start + report.end()
    action_start, action_end = action_spans[0]
    food_start, food_end = food_spans[0]
    action_fragment = clean[action_start:action_end]
    return bool(
        action_start >= report_start
        and action_end <= report_end
        and _SEMANTIC_MEAL_CONSUMPTION_CUE.search(action_fragment)
        and food_start >= report_end
        and food_end <= len(clean)
        and re.search(r"[A-Za-zА-Яа-яЁё]", clean[food_start:food_end])
    )


def is_explicit_additional_meal_report(text: str) -> bool:
    """Distinguish a separate meal from a terse correction of a recent one."""

    clean = " ".join((text or "").strip().split())
    return bool(
        has_explicit_meal_consumption(clean)
        and _ADDITIONAL_MEAL_MARKER.search(clean)
    )


def has_semantic_meal_consumption_cue(text: str) -> bool:
    """Detect a completed-meal cue only to prevent partial mixed writes.

    This is a deny-only invariant, never authority to create a meal.  It is
    deliberately broader than ``has_explicit_meal_consumption`` so an unseen
    conjunction cannot make the server silently save only the insulin clause.
    """

    return _SEMANTIC_MEAL_CONSUMPTION_CUE.search(text or "") is not None


def is_explicit_meal_correction(text: str) -> bool:
    """Authorize meal replacement only for one anchored correction report."""

    clean = " ".join((text or "").strip().split())
    if not has_explicit_meal_consumption(clean):
        return False
    return bool(
        _ENGLISH_MEAL_CORRECTION.fullmatch(clean)
        or _RUSSIAN_MEAL_CORRECTION.fullmatch(clean)
    )


def has_safe_photo_meal_context(text: str) -> bool:
    """Authorize photo logging only without a caption or with a self-report.

    Arbitrary captions cannot be classified safely with a denylist: a question,
    instruction, or prompt injection always has another paraphrase.  Photo-only
    is an intentional logging flow; every nonempty caption must independently
    prove that the user consumed the meal.
    """

    clean = " ".join((text or "").strip().split())
    if not clean:
        return True
    return has_explicit_meal_consumption(clean)


def parse_relative_meal_time_offset_ms(text: str) -> int | None:
    """Parse one bounded relative-past meal time such as ``2 hours ago``."""

    clean = " ".join((text or "").strip().split())
    matches = list(_RELATIVE_MEAL_TIME.finditer(clean))
    if len(matches) != 1:
        return None
    match = matches[0]
    residual = clean[: match.start()] + " " + clean[match.end() :]
    if _PAST_TIME_MARKER.search(residual):
        return None
    raw_number = match.group("number")
    value = _RU_SPOKEN_NUMBERS.get(raw_number.casefold())
    if value is None:
        value = _parse_units(raw_number)
    unit = match.group("unit").casefold()
    is_hour = unit.startswith(("hour", "hr", "час"))
    maximum = 168 if is_hour else 10_000
    if value <= 0 or value > maximum:
        return None
    multiplier = 60 * 60 * 1_000 if is_hour else 60 * 1_000
    return round(value * multiplier)


def has_ambiguous_meal_time_reference(text: str) -> bool:
    clean = " ".join((text or "").strip().split())
    return bool(_PAST_TIME_MARKER.search(clean)) and (
        parse_relative_meal_time_offset_ms(clean) is None
    )


def parse_contextual_insulin_dose_correction(
    text: str,
) -> ContextualInsulinDoseCorrection | None:
    """Return OLD and NEW for an exact product-less dose correction.

    Product resolution is deliberately outside this helper.  A caller may use
    the value only when trusted session state identifies exactly one insulin
    target; this function never guesses one from conversational text.
    """

    clean = _normalize_spoken_dose_numbers(
        " ".join((text or "").strip().split())
    )
    if "?" in clean:
        return None
    for pattern in _CONTEXTUAL_DOSE_CORRECTIONS:
        match = pattern.fullmatch(clean)
        if match is None:
            continue
        old_units = _parse_units(match.group("old"))
        new_units = _parse_units(match.group("new"))
        if 0 < old_units <= 500 and 0 < new_units <= 500:
            return ContextualInsulinDoseCorrection(old_units, new_units)
        return None

    # Russian voice transcription commonly emits number words.  The same
    # strict full-expression rule applies; product selection still belongs to
    # trusted session context, never to this parser.
    spoken = _RUSSIAN_EXPLICIT_CORRECTION.fullmatch(clean)
    if spoken is not None:
        old_units = _exact_correction_dose(spoken.group("old"))
        new_units = _exact_correction_dose(spoken.group("new"))
        if old_units is not None and new_units is not None:
            return ContextualInsulinDoseCorrection(old_units, new_units)
    return None


def _bounded_dose_token_value(raw: str) -> float | None:
    token = " ".join((raw or "").casefold().split())
    value = _RU_SPOKEN_NUMBERS.get(token)
    if value is None:
        try:
            value = _parse_units(token)
        except ValueError:
            return None
    return value if 0 < value <= 500 else None


def _single_product_selector(
    text: str,
) -> tuple[str, str] | None | bool:
    """Return one product pair, ``None`` for generic, or ``False`` for conflict."""

    products = {
        _canonical_product(match.group()) for match in _KNOWN_PRODUCT.finditer(text)
    }
    if len(products) > 1:
        return False
    return next(iter(products)) if products else None


def parse_terse_insulin_dose_replacement(
    text: str,
) -> TerseInsulinDoseReplacement | None:
    """Parse a short correction containing one new insulin dose.

    This validates only current-turn evidence.  A product-less result is safe
    to apply only when the caller has frozen exactly one recent insulin event;
    an explicit generic/product referent may be resolved against frozen
    session-owned events.  The helper never chooses an event itself.
    """

    raw = text or ""
    clean = " ".join(raw.strip().split())
    if (
        not clean
        or len(clean) > 240
        or len(clean.split()) > 32
        or "\n" in raw
        or "\r" in raw
        or ";" in clean
        or "?" in clean
        or re.search(r"[.!?]\s*\S", clean) is not None
        or _TERSE_INSULIN_CORRECTION_CUE.search(clean) is None
        or _QUESTION.search(clean) is not None
        or _INFO_REQUEST.search(clean) is not None
        or _FUTURE_OR_PLAN.search(clean) is not None
        or _CONDITIONAL.search(clean) is not None
        or _UNCERTAINTY.search(clean) is not None
        or _RANGE_OR_ALTERNATIVE.search(clean) is not None
        or _WRONG_UNIT_NEXT_TO_NUMBER.search(clean) is not None
        or _GLUCOSE_VALUE_CONTEXT.search(clean) is not None
        or _TIME_VALUE_CONTEXT.search(clean) is not None
        or _QUOTED_OR_LABEL.search(clean) is not None
        or _PROMPT_INJECTION.search(clean) is not None
        or _NEGATED_MUTATION_CONTROL.search(clean) is not None
        or _NON_SELF_ACTOR_CUE.search(clean) is not None
        or _SEMANTIC_MEAL_CONSUMPTION_CUE.search(clean) is not None
        or _COMPLETED_INSULIN_ACTION.search(clean) is not None
    ):
        return None

    selector = _single_product_selector(clean)
    if selector is False:
        return None
    dose_matches = list(_SEMANTIC_BOUNDED_DOSE_TOKEN.finditer(clean))
    dose_values = [
        value
        for match in dose_matches
        if (value := _bounded_dose_token_value(match.group())) is not None
    ]
    if len(dose_values) != len(dose_matches) or not dose_values:
        return None
    distinct_values = {round(value, 6) for value in dose_values}
    if len(distinct_values) != 1:
        return None

    product_spans = [match.span() for match in _KNOWN_PRODUCT.finditer(clean)]
    residual = _without_spans(
        clean,
        [match.span() for match in dose_matches] + product_spans,
    )
    residual = _GENERIC_PRODUCT.sub(" ", residual)
    residual = _TERSE_INSULIN_ALLOWED_FILLER.sub(" ", residual)
    residual = re.sub(r"[\s,;:.!()\[\]{}+\-–—/]+", " ", residual).strip()
    if residual:
        return None

    product_pair = selector if isinstance(selector, tuple) else None
    has_explicit_referent = bool(
        product_pair is not None
        or _GENERIC_PRODUCT.search(clean)
        or _TERSE_INSULIN_UNIT_REFERENT.search(clean)
    )
    if (
        not has_explicit_referent
        and _TERSE_INSULIN_STRONG_CORRECTION_CUE.search(clean) is None
    ):
        return None
    return TerseInsulinDoseReplacement(
        replacement_units=dose_values[0],
        insulin_name=product_pair[0] if product_pair is not None else None,
        insulin_type=product_pair[1] if product_pair is not None else None,
        has_explicit_referent=has_explicit_referent,
    )


def has_contextual_insulin_time_correction_cue(text: str) -> bool:
    """Detect an insulin timestamp-edit shape without authorizing a write."""

    clean = " ".join((text or "").strip().split())
    has_insulin_referent = bool(
        _KNOWN_PRODUCT.search(clean) or _GENERIC_PRODUCT.search(clean)
    )
    has_time_reference = bool(
        _PAST_TIME_MARKER.search(clean)
        or re.search(
            r"\b(?:tomorrow|later|in\s+\d+\s+(?:minutes?|hours?)|"
            r"завтра|позже|через\s+\S+\s+(?:минут\w*|час\w*))\b",
            clean,
            flags=re.IGNORECASE,
        )
    )
    return bool(
        clean
        and has_insulin_referent
        and has_time_reference
        and _INSULIN_TIME_CORRECTION_HINT.search(clean)
    )


def parse_contextual_insulin_time_correction(
    text: str,
) -> ContextualInsulinTimeCorrection | None:
    """Parse a strict relative-past correction of an insulin timestamp.

    The phrase must explicitly refer to insulin and explicitly frame the time
    as an edit (for example ``not now, but 5 minutes ago``).  This prevents an
    ordinary historical injection report from being mistaken for a revision.
    Target selection and compare-and-replace remain the caller's job.
    """

    raw = text or ""
    clean = " ".join(raw.strip().split())
    if (
        not has_contextual_insulin_time_correction_cue(clean)
        or len(clean) > 320
        or len(clean.split()) > 40
        or "\n" in raw
        or "\r" in raw
        or ";" in clean
        or "?" in clean
        or re.search(r"[.!?]\s*\S", clean) is not None
        or _QUESTION.search(clean) is not None
        or _INFO_REQUEST.search(clean) is not None
        or _FUTURE_OR_PLAN.search(clean) is not None
        or _CONDITIONAL.search(clean) is not None
        or _UNCERTAINTY.search(clean) is not None
        or _RANGE_OR_ALTERNATIVE.search(clean) is not None
        or _QUOTED_OR_LABEL.search(clean) is not None
        or _PROMPT_INJECTION.search(clean) is not None
        or _NEGATED_MUTATION_CONTROL.search(clean) is not None
        or _NON_SELF_ACTOR_CUE.search(clean) is not None
        or _SEMANTIC_MEAL_CONSUMPTION_CUE.search(clean) is not None
    ):
        return None

    selector = _single_product_selector(clean)
    if selector is False:
        return None
    offset_ms = parse_relative_meal_time_offset_ms(clean)
    relative_matches = list(_RELATIVE_MEAL_TIME.finditer(clean))
    if offset_ms is None or len(relative_matches) != 1:
        return None

    product_spans = [match.span() for match in _KNOWN_PRODUCT.finditer(clean)]
    action_spans = [
        match.span() for match in _COMPLETED_INSULIN_ACTION.finditer(clean)
    ]
    residual = _without_spans(
        clean,
        [relative_matches[0].span()] + product_spans + action_spans,
    )
    residual = _GENERIC_PRODUCT.sub(" ", residual)
    residual = _INSULIN_TIME_ALLOWED_FILLER.sub(" ", residual)
    residual = re.sub(r"[\s,;:.!()\[\]{}+\-–—/]+", " ", residual).strip()
    if residual:
        return None

    product_pair = selector if isinstance(selector, tuple) else None
    return ContextualInsulinTimeCorrection(
        offset_ms=offset_ms,
        insulin_name=product_pair[0] if product_pair is not None else None,
        insulin_type=product_pair[1] if product_pair is not None else None,
    )


def parse_contextual_meal_quantity_correction(
    text: str,
) -> ContextualMealQuantityCorrection | None:
    """Return OLD and NEW grams for one strict product-less meal correction."""

    clean = _normalize_spoken_meal_grams(
        " ".join((text or "").strip().split())
    )
    for pattern in _CONTEXTUAL_MEAL_QUANTITY_CORRECTIONS:
        match = pattern.fullmatch(clean)
        if match is None:
            continue
        old_grams = _parse_units(match.group("old"))
        new_grams = _parse_units(match.group("new"))
        if 0 < old_grams <= 10_000 and 0 < new_grams <= 10_000:
            return ContextualMealQuantityCorrection(old_grams, new_grams)
        return None
    return None


def parse_terse_meal_portion_replacement(
    text: str,
    *,
    expected_current_grams: float | None = None,
) -> float | None:
    """Extract bounded portion evidence from one short follow-up turn.

    This helper validates evidence only.  It does not decide that the user
    intended a correction and it does not select or authorize a meal target.
    The caller must establish that separately from frozen conversation state.

    One or more identical, explicitly gram-qualified assertions are sufficient.
    A two-value old-to-new fragment may omit either or both gram units, but only
    when the caller supplies the frozen current portion and the first value
    exactly matches it.  A lone unitless number is intentionally ambiguous.
    """

    clean = _normalize_spoken_meal_grams(
        " ".join((text or "").strip().split())
    )
    if (
        not clean
        or len(clean) > _TERSE_MEAL_PORTION_MAX_CHARS
        or len(clean.split()) > _TERSE_MEAL_PORTION_MAX_TOKENS
        or _QUESTION.search(clean)
        or _INFO_REQUEST.search(clean)
        or _UNCERTAINTY.search(clean)
        or _TERSE_MEAL_PORTION_RANGE.search(clean)
        or _TERSE_MEAL_INSULIN_UNITS.search(clean)
        or _TERSE_MEAL_WRONG_PORTION_UNITS.search(clean)
        or _TERSE_MEAL_CARB_MASS.search(clean)
        or _TERSE_MEAL_SIGNED_PORTION.search(clean)
    ):
        return None

    values: list[float] = []
    for match in _TERSE_MEAL_PORTION_VALUE.finditer(clean):
        raw = match.group("value")
        value = _RU_SPOKEN_NUMBERS.get(raw.casefold())
        if value is None:
            value = _parse_units(raw)
        if not 0 < value <= 10_000:
            return None
        values.append(value)

    gram_values = [
        _parse_units(match.group("value"))
        for match in _TERSE_MEAL_PORTION_GRAMS.finditer(clean)
    ]
    if not values:
        return None

    # Repeating the same asserted value (for example, an ASR transcript plus a
    # typed echo) adds no ambiguity and is treated as one piece of evidence.
    transitions = [values[0]]
    for value in values[1:]:
        if abs(value - transitions[-1]) > 1e-6:
            transitions.append(value)

    if len(transitions) == 1:
        return transitions[0] if gram_values else None

    if (
        len(transitions) == 2
        and expected_current_grams is not None
        and 0 < expected_current_grams <= 10_000
        and abs(transitions[0] - expected_current_grams) <= 1e-6
    ):
        return transitions[1]
    return None


def is_safe_terse_meal_revision_text(
    text: str,
    *,
    expected_current_grams: float | None = None,
) -> bool:
    """Deny structurally unsafe text before trusted recent-meal semantics."""

    raw = text or ""
    clean = " ".join(raw.strip().split())
    has_explicit_portion_evidence = bool(
        _TERSE_MEAL_PORTION_RANGE.search(clean)
        or _TERSE_MEAL_PORTION_GRAMS.search(clean)
    )
    portion_is_unambiguous = bool(
        not has_explicit_portion_evidence
        or parse_terse_meal_portion_replacement(
            clean,
            expected_current_grams=expected_current_grams,
        )
        is not None
    )
    consumption_actor_is_safe = bool(
        _SEMANTIC_MEAL_CONSUMPTION_CUE.search(clean) is None
        or has_explicit_meal_consumption(clean)
    )
    return bool(
        clean
        and len(clean) <= _TERSE_MEAL_PORTION_MAX_CHARS
        and len(clean.split()) <= _TERSE_MEAL_PORTION_MAX_TOKENS
        and "\n" not in raw
        and "\r" not in raw
        and ";" not in clean
        and "?" not in clean
        and re.search(r"[.!?]\s*\S", clean) is None
        and _QUESTION.search(clean) is None
        and _INFO_REQUEST.search(clean) is None
        and _FUTURE_OR_PLAN.search(clean) is None
        and _CONDITIONAL.search(clean) is None
        and _UNCERTAINTY.search(clean) is None
        and _OTHER_PERSON_MEAL_REPORT.search(clean) is None
        and _NON_SELF_ACTOR_CUE.search(clean) is None
        and _POSTPOSED_NAMED_MEAL_ACTOR.search(clean) is None
        and _POSTPOSED_COMMON_MEAL_ACTOR.search(clean) is None
        and _QUOTED_OR_LABEL.search(clean) is None
        and _PROMPT_INJECTION.search(clean) is None
        and _NEGATED_MUTATION_CONTROL.search(clean) is None
        and _GLUCOSE_VALUE_CONTEXT.search(clean) is None
        and _INSULIN_LIKE_TOKEN.search(clean) is None
        and _GENERIC_INSULIN.search(clean) is None
        and _UNATTACHED_DOSE.search(clean) is None
        and _TERSE_MEAL_GREETING_ONLY.fullmatch(clean) is None
        and re.search(r"[A-Za-zА-Яа-яЁё]", clean) is not None
        and portion_is_unambiguous
        and consumption_actor_is_safe
        and not has_ambiguous_meal_time_reference(clean)
    )


def parse_exact_insulin_dose(text: str) -> float | None:
    """Return one bounded dose-only reply, without supplying a product."""

    clean = " ".join((text or "").strip().split())
    if not clean:
        return None
    return _exact_correction_dose(clean)


def semantic_dose_evidence_matches(
    text: str,
    units: float,
    *,
    allow_inflected_ordinal: bool,
) -> bool:
    """Corroborate a model dose against its quoted current-turn fragment.

    Ordinary numeric/cardinal evidence stays fully deterministic.  The semantic
    exception is a finite exact morphology map; it never guesses a value from a
    shared word prefix or consonant stem.
    """

    exact = parse_exact_insulin_dose(text)
    if exact is not None:
        return abs(exact - units) <= 1e-6
    clean = " ".join((text or "").strip().split()).casefold()
    mapped = (
        _RU_INFLECTED_DOSE_NUMBERS.get(clean)
        if allow_inflected_ordinal
        else None
    )
    return mapped is not None and abs(mapped - units) <= 1e-6


def semantic_text_has_bounded_dose_evidence(text: str) -> bool:
    """Detect a standalone bounded numeric/spoken value without inferring intent."""

    return _SEMANTIC_BOUNDED_DOSE_TOKEN.search(text or "") is not None


def semantic_dose_context_is_safe(
    text: str,
    dose_span: tuple[int, int],
) -> bool:
    """Reject a quoted dose that is actually a measurement or time value.

    Only deny patterns overlapping the exact provider-quoted dose are used.
    This prevents a glucose/time/volume number from authorizing insulin while
    allowing unrelated measurements elsewhere in the same completed report.
    """

    clean = " ".join((text or "").strip().split())
    start, end = dose_span
    if start < 0 or end <= start or end > len(clean):
        return False
    for pattern in (
        _WRONG_UNIT_NEXT_TO_NUMBER,
        _GLUCOSE_VALUE_CONTEXT,
        _TIME_VALUE_CONTEXT,
    ):
        if any(
            match.start() < end and match.end() > start
            for match in pattern.finditer(clean)
        ):
            return False
    return True


def semantic_dose_values_are_consistent(
    text: str,
    accepted_units: float,
) -> bool:
    """Reject conflicting asserted dose values around semantic evidence.

    The provider may select which verbatim number represents the dose, but it
    may not silently choose between two different asserted values.  Identical
    STT repetitions are harmless, and a different value is ignored only when
    it is locally and explicitly negated (``not 5`` / ``не 5``).  Numbers that
    are deterministically measurements, meal amounts, or times are excluded by
    the same context gate used for the selected dose.
    """

    clean = " ".join((text or "").strip().split())
    accepted_found = False
    for match in _SEMANTIC_BOUNDED_DOSE_TOKEN.finditer(clean):
        span = match.span()
        if not semantic_dose_context_is_safe(clean, span):
            continue
        fragment = match.group()
        if semantic_dose_evidence_matches(
            fragment,
            accepted_units,
            allow_inflected_ordinal=True,
        ):
            accepted_found = True
            continue
        prefix = clean[max(0, span[0] - 24) : span[0]]
        if _LOCALLY_NEGATED_DOSE_PREFIX.search(prefix) is None:
            return False
    return accepted_found


def _semantic_phonetic_product_matches(
    token: str,
    expected_pair: tuple[str, str],
) -> bool:
    """Match one compact product phrase by a deliberately narrow ASR rule."""

    normalized = " ".join(
        (token or "").casefold().replace("ё", "е").replace("-", " ").split()
    )
    aliases = {
        ("NovoRapid", "rapid"): {
            *(
                " ".join(alias.casefold().replace("ё", "е").split())
                for alias in _RAPID_ASR_ALIASES
            ),
            "наваперда",
            "нава перда",
            "воропида",
        },
        ("Tresiba", "long"): set(),
    }.get(expected_pair, set())
    return bool(normalized and normalized in aliases)


def _semantic_product_relative_span(
    evidence: str,
    insulin_name: str,
    insulin_type: str,
) -> tuple[int, int] | None:
    """Return one unambiguous canonical/phonetic product subspan.

    Providers sometimes include the adjacent dose in ``product_evidence``.
    Candidate discovery therefore happens inside the exact provider quote, but
    it remains conservative: a conflicting known product rejects the quote,
    phonetic matching covers at most two adjacent letter-only words, and only
    one maximal candidate may survive.  A larger phonetic candidate wins over
    a nested generic token (for example ``нава рапида`` over ``рапида``).
    """

    clean = " ".join((evidence or "").strip().split())
    if not clean:
        return None
    expected_pair = (insulin_name, insulin_type)
    known = list(_KNOWN_PRODUCT.finditer(clean))
    if any(_canonical_product(match.group()) != expected_pair for match in known):
        return None

    candidates: dict[tuple[int, int], bool] = {
        match.span(): True for match in known
    }
    words = list(_PRODUCT_EVIDENCE_WORD.finditer(clean))
    for index, first in enumerate(words):
        for word_count in (1, 2):
            last_index = index + word_count - 1
            if last_index >= len(words):
                continue
            last = words[last_index]
            if word_count == 2 and re.fullmatch(
                r"[\s-]+", clean[first.end() : last.start()]
            ) is None:
                continue
            span = (first.start(), last.end())
            if _semantic_phonetic_product_matches(
                clean[slice(*span)], expected_pair
            ):
                candidates[span] = candidates.get(span, False)

    if not candidates:
        return None
    maximal = [
        span
        for span in candidates
        if not any(
            other != span
            and other[0] <= span[0]
            and other[1] >= span[1]
            for other in candidates
        )
    ]
    if len(maximal) != 1:
        return None

    chosen = maximal[0]
    if candidates[chosen]:
        return chosen

    # A phonetic token is weaker than canonical morphology.  Permit surrounding
    # text only when it is exactly one bounded dose (and optional insulin unit),
    # which is independently value-checked by the caller.  This admits provider
    # quotes such as "пятого наваперда" without allowing an
    # arbitrary word plus a product-looking hallucination.
    residual = " ".join(
        (clean[: chosen[0]] + " " + clean[chosen[1] :]).strip().split()
    )
    residual = re.sub(r"^[\s,.:;()\[\]{}+\-–—/]+", "", residual)
    residual = re.sub(r"[\s,.:;()\[\]{}+\-–—/]+$", "", residual)
    if not residual:
        return chosen
    if re.fullmatch(
        rf"{_SEMANTIC_DOSE_TOKEN_BODY}(?:\s*{_UNIT})?",
        residual,
        flags=re.IGNORECASE,
    ) is None:
        return None
    return chosen


def semantic_product_evidence_matches(
    text: str,
    insulin_name: str,
    insulin_type: str,
) -> bool:
    """Corroborate a canonical product with exact or strict phonetic evidence."""

    return (
        _semantic_product_relative_span(text, insulin_name, insulin_type)
        is not None
    )


def semantic_product_evidence_span(
    source: str,
    evidence: str | None,
    insulin_name: str,
    insulin_type: str,
) -> tuple[int, int] | None:
    """Locate only the corroborated product inside one provider quote.

    A model may conservatively quote a phrase such as ``пятого рапида`` as
    product evidence.  The dose is part of that quote, but it must not become
    part of the product span used by the independent dose/product binding
    check.  Preserve the existing product matcher as the authority gate, then
    narrow a unique whole quote to its single canonical product token.
    """

    clean_source = " ".join((source or "").strip().split())
    clean_evidence = " ".join((evidence or "").strip().split())
    relative_span = _semantic_product_relative_span(
        clean_evidence,
        insulin_name,
        insulin_type,
    )
    if not clean_evidence or relative_span is None:
        return None

    evidence_matches = list(
        re.finditer(
            r"(?<![\w'’\-])"
            + re.escape(clean_evidence)
            + r"(?![\w'’\-])",
            clean_source,
            flags=re.IGNORECASE,
        )
    )
    if len(evidence_matches) != 1:
        return None

    evidence_start = evidence_matches[0].start()
    return (
        evidence_start + relative_span[0],
        evidence_start + relative_span[1],
    )


def _normalize_supported_insulin_action_asr(text: str) -> str:
    """Repair ``я около`` only inside one complete insulin fact.

    Russian Whisper can drop the first and final consonants of ``я уколол``.
    Outside a complete product-plus-dose command the phrase remains untouched,
    so ordinary approximate quantities never gain write authority.
    """

    if (
        _SELF_INJECTION_ACTION_ASR.search(text) is None
        or len(_command_matches(text)) != 1
    ):
        return text
    return _SELF_INJECTION_ACTION_ASR.sub("я уколол", text, count=1)


def parse_contextual_new_insulin_dose(text: str) -> float | None:
    """Return a bounded product-less dose only from an explicit new injection.

    Product resolution remains the caller's responsibility and is safe only
    from a short-lived, same-session single-insulin context.  A plain dose is
    intentionally excluded because the chat uses that shape as a correction of
    the immediately preceding card.
    """

    clean = " ".join((text or "").strip().split())
    if not clean:
        return None
    for pattern in _CONTEXTUAL_NEW_INSULIN_DOSE:
        match = pattern.fullmatch(clean)
        if match is None:
            continue
        return _exact_correction_dose(match.group("dose"))
    return None


def is_explicit_new_insulin_report(text: str) -> bool:
    """Distinguish a reported new injection from a correction payload."""

    clean = " ".join((text or "").strip().split())
    return bool(
        clean
        and (
            _EXPLICIT_NEW_INSULIN_MARKER.search(clean)
            or _EXPLICIT_TRAILING_MORE_INSULIN.search(clean)
            or _SELF_INJECTION_ACTION_ASR.search(clean)
        )
    )


def parse_insulin_product_missing_dose(
    text: str,
) -> IncompleteInsulinProduct | None:
    """Recognize one strict product report for a subsequent dose clarification.

    This helper deliberately does not accept the ASR aliases above without a
    neighboring dose.  It also never infers a malformed number.  The exact
    ``пятного`` token is allowed solely because Whisper has been observed to
    merge ``пять Ново`` into it; it remains an unresolved dose, not the value 5.
    """

    clean = " ".join((text or "").strip().split())
    if not clean or _unsafe_context(clean, allow_correction_negation=False):
        return None

    products = list(_KNOWN_PRODUCT.finditer(clean))
    if len(products) != 1:
        return None
    product = products[0]
    residual = _without_spans(clean, [product.span()])
    if _has_unmatched_product(residual):
        return None

    residual = _INJECTION_WRAPPER.sub(" ", residual)
    residual = re.sub(r"[\s,;:.!?()\[\]{}+\-–—/]+", " ", residual).strip()
    if residual.casefold() not in ("", "пятного"):
        return None

    name, insulin_type = _canonical_product(product.group())
    return IncompleteInsulinProduct(name, insulin_type)


def is_safe_semantic_insulin_text(text: str) -> bool:
    """Apply syntax/injection safety before semantic classification.

    This deliberately has no vocabulary allowlist for administration or
    correction language.  Meaning belongs to the strict semantic model; local
    code only rejects structurally compound or adversarial input.
    """

    raw = text or ""
    clean = " ".join(raw.strip().split())
    return bool(
        clean
        and "\n" not in raw
        and "\r" not in raw
        and ";" not in clean
        and re.search(r"[.!?]\s*\S", clean) is None
        and _PROMPT_INJECTION.search(clean) is None
    )


def _semantic_insulin_actor_is_self_or_subjectless(
    text: str,
    insulin_span: tuple[int, int] | None,
    *,
    action_span: tuple[int, int] | None,
    enforce_structural_subject: bool,
) -> bool:
    """Reject an arbitrary grammatical subject before a completed action.

    The semantic provider still classifies ``actor``, but a high-confidence
    misclassification must not turn ``John injected ...`` into the user's
    record.  Explicit self pronouns and natural subjectless diary shorthand
    remain valid.  Product/dose anchor text is removed before narrow checks on
    both sides of the action, including passive/postposed actors.
    """

    resolved = _semantic_insulin_clause_bounds(text, insulin_span)
    if resolved is None:
        return False
    clean, clause_start, clause_end = resolved
    clause = clean[clause_start:clause_end]
    if _POSTPOSED_BY_NON_SELF_ACTOR.search(clause) is not None:
        return False
    if enforce_structural_subject and insulin_span is not None:
        anchor_start = max(0, insulin_span[0] - clause_start)
        anchor_end = min(len(clause), insulin_span[1] - clause_start)
        if action_span is None:
            deterministic_actions = list(_COMPLETED_INSULIN_ACTION.finditer(clause))
            if len(deterministic_actions) != 1:
                return False
            action_start, action_end = deterministic_actions[0].span()
        else:
            action_start = max(0, action_span[0] - clause_start)
            action_end = min(len(clause), action_span[1] - clause_start)
        if action_end <= action_start:
            return False

        # The provider is required to quote the shortest action fragment. Keep
        # that fragment as the permissible unknown verb, then inspect every
        # other word after removing the independently verified action and
        # product/dose spans. This accepts unseen natural action verbs without
        # an action-word allowlist while rejecting ``John <verb> 5 NovoRapid``
        # and postposed ``5 NovoRapid by John`` if actor=self was misclassified.
        masked = list(clause)
        for start, end in (
            (anchor_start, anchor_end),
            (action_start, action_end),
        ):
            for index in range(max(0, start), min(len(masked), end)):
                masked[index] = " "
        outside_evidence_words = [
            match.group().casefold().replace("ё", "е")
            for match in _PRODUCT_EVIDENCE_WORD.finditer("".join(masked))
        ]
        outside_content = [
            word
            for word in outside_evidence_words
            if word not in _SEMANTIC_ACTOR_FILLER_WORDS
            and word not in _SEMANTIC_ACTOR_SELF_WORDS
        ]
        if outside_content:
            return False

        action_fragment = clause[action_start:action_end]
        if (
            _NON_SELF_ACTOR_CUE.search(action_fragment) is not None
            or _POSTPOSED_NAMED_MEAL_ACTOR.search(action_fragment) is not None
            or _POSTPOSED_COMMON_MEAL_ACTOR.search(action_fragment) is not None
        ):
            return False
        action_without_anchor = list(action_fragment)
        overlap_start = max(action_start, anchor_start) - action_start
        overlap_end = min(action_end, anchor_end) - action_start
        if overlap_end > overlap_start:
            for index in range(overlap_start, overlap_end):
                action_without_anchor[index] = " "
        action_word_matches = list(
            _PRODUCT_EVIDENCE_WORD.finditer("".join(action_without_anchor))
        )
        action_content = [
            match
            for match in action_word_matches
            if match.group().casefold().replace("ё", "е")
            not in _SEMANTIC_ACTOR_FILLER_WORDS
            and match.group().casefold().replace("ё", "е")
            not in _SEMANTIC_ACTOR_SELF_WORDS
        ]
        if (
            len(action_content) > 1
            or (
                len(action_content) == 1
                and _COMMON_PERSON_NAME_TOKEN.fullmatch(action_content[0].group())
                is not None
            )
        ):
            return False
        if len(action_content) == 1:
            content_match = action_content[0]
            content_start = action_start + content_match.start()
            if (
                content_start >= anchor_end
                and _COMPLETED_INSULIN_ACTION.fullmatch(content_match.group())
                is None
            ):
                # A lone unexplained token after product+dose is more likely a
                # postposed actor than a subjectless action. Permit it only when
                # deterministic completed-action morphology is known.
                return False
    for action in _COMPLETED_INSULIN_ACTION.finditer(clause):
        prefix = clause[: action.start()]
        suffix = clause[action.end() :]
        if insulin_span is not None:
            anchor_start = max(0, insulin_span[0] - clause_start)
            anchor_end = min(len(clause), insulin_span[1] - clause_start)
            if anchor_start < min(action.start(), anchor_end):
                prefix_end = min(action.start(), anchor_end)
                prefix = prefix[:anchor_start] + " " + prefix[prefix_end:]
            suffix_anchor_start = max(action.end(), anchor_start)
            if suffix_anchor_start < anchor_end:
                relative_start = suffix_anchor_start - action.end()
                relative_end = anchor_end - action.end()
                suffix = suffix[:relative_start] + " " + suffix[relative_end:]
        if _SELF_OR_SUBJECTLESS_ACTION_PREFIX.fullmatch(prefix) is None:
            return False
        if (
            insulin_span is not None
            and _SELF_OR_SUBJECTLESS_ACTION_SUFFIX.fullmatch(suffix) is None
        ):
            return False
    return True


def is_safe_semantic_insulin_write(
    text: str,
    *,
    intent: str,
    insulin_span: tuple[int, int] | None = None,
    action_span: tuple[int, int] | None = None,
) -> bool:
    """Apply independent deny-only safety checks to semantic actions.

    These patterns never authorize a write or identify a dose/product.  They
    only veto unsafe polarity, temporality, questions, non-self actors, quoted
    reports, recommendations, and structurally adversarial input if a model
    misclassifies the turn.
    """

    clean = " ".join((text or "").strip().split())
    insulin_clause = _semantic_insulin_clause(text, insulin_span)
    common_safe = bool(
        is_safe_semantic_insulin_text(text)
        and "?" not in (text or "")
        and bool(insulin_clause)
        and _QUESTION.search(clean) is None
        and _INFO_REQUEST.search(clean) is None
        and _FUTURE_OR_PLAN.search(insulin_clause) is None
        and _CONDITIONAL.search(insulin_clause) is None
        and _UNCERTAINTY.search(insulin_clause) is None
        and _NON_SELF_ACTOR_CUE.search(insulin_clause) is None
        and _semantic_insulin_actor_is_self_or_subjectless(
            text,
            insulin_span,
            action_span=action_span,
            enforce_structural_subject=intent in ("create", "replace_last"),
        )
        and _BARE_TREATMENT_QUESTION.search(insulin_clause) is None
        and _QUOTED_OR_LABEL.search(clean) is None
        and _RECOMMENDATION.search(clean) is None
        and _RANGE_OR_ALTERNATIVE.search(insulin_clause) is None
        and _SEMANTIC_ALTERNATIVE_CUE.search(insulin_clause) is None
        and _WRONG_UNIT_NEXT_TO_NUMBER.search(insulin_clause) is None
        and _PROMPT_INJECTION.search(clean) is None
    )
    if (
        not common_safe
        or _NEGATED_MUTATION_CONTROL.search(clean) is not None
    ):
        return False
    if intent == "create":
        return _NEGATION.search(insulin_clause) is None
    if intent in ("replace_last", "delete_last"):
        return _NEGATED_INSULIN_ACTION.search(insulin_clause) is None
    return intent == "revise_last"


def parse_explicit_insulin(text: str) -> ExplicitInsulinParse:
    """Parse a conservative allowlist of explicitly reported insulin facts.

    Anything outside a complete product + numeric-units clause is deliberately
    ambiguous on this deterministic fast path.  A separate strict semantic
    fallback may extract only current-turn, evidence-quoted self-reports and is
    independently validated before the orchestrator can apply them.
    """

    clean = " ".join((text or "").strip().split())
    clean = _normalize_rapid_asr_aliases(clean)
    clean = _normalize_spoken_dose_numbers(clean)
    clean = _normalize_supported_insulin_action_asr(clean)
    replace_requested = bool(_REPLACE_HINT.search(clean))
    if not clean:
        return ExplicitInsulinParse((), False, replace_requested, "")

    # This is a real correction shape, but it has no product.  Keep it away
    # from the meal LLM and require the caller to resolve it with the narrow
    # contextual helper above.
    if parse_contextual_insulin_dose_correction(clean) is not None:
        return ExplicitInsulinParse((), True, True, "")

    has_hint = _has_insulin_hint(clean)
    if not has_hint:
        return ExplicitInsulinParse((), False, replace_requested, clean)

    explicit_correction = bool(
        _ENGLISH_EXPLICIT_CORRECTION.search(clean)
        or _RUSSIAN_EXPLICIT_CORRECTION.search(clean)
    )
    if _unsafe_context(clean, allow_correction_negation=explicit_correction):
        return ExplicitInsulinParse((), True, replace_requested, "")

    if explicit_correction:
        correction = _parse_product_correction(clean)
        if correction is None:
            return ExplicitInsulinParse((), True, True, "")
        replacement, target_type, expected_units = correction
        return ExplicitInsulinParse(
            (replacement,),
            False,
            True,
            "",
            insulin_replace_requested=True,
            insulin_replace_target_type=target_type,
            insulin_replace_expected_units=expected_units,
        )

    all_matches = _command_matches(clean)
    if not all_matches:
        return ExplicitInsulinParse((), True, replace_requested, "")
    if any(command.insulin_units <= 0 or command.insulin_units > 500 for command in all_matches):
        return ExplicitInsulinParse((), True, replace_requested, "")

    # Every recognized product token must be consumed by a complete command.
    residual_with_products = _without_spans(
        clean, [command.span for command in all_matches]
    )
    if _has_unmatched_product(residual_with_products):
        return ExplicitInsulinParse((), True, replace_requested, "")

    by_product: dict[str, list[ExplicitInsulinCommand]] = {}
    for command in all_matches:
        by_product.setdefault(command.insulin_name, []).append(command)
    if any(len(commands) > 1 for commands in by_product.values()):
        return ExplicitInsulinParse((), True, replace_requested, "")
    selected = list(all_matches)

    residual = _without_spans(clean, [command.span for command in all_matches])

    if _allowed_command_context(residual):
        # A correction marker without an explicit OLD -> NEW expression lacks
        # the expected dose required for compare-and-replace.  Never silently
        # turn it into either a replacement or a duplicate create.
        if replace_requested:
            return ExplicitInsulinParse((), True, True, "")
        return ExplicitInsulinParse(
            tuple(selected),
            False,
            replace_requested,
            "",
        )

    meal_evidence = _meal_only_residual(residual)
    if meal_evidence:
        return ExplicitInsulinParse(
            tuple(selected), False, replace_requested, meal_evidence
        )
    return ExplicitInsulinParse((), True, replace_requested, "")


def is_explicit_undo(text: str) -> bool:
    return bool(_UNDO_ONLY.fullmatch((text or "").strip()))


def is_explicit_delete_current(text: str) -> bool:
    """Recognize deletion of the visible current entry, not action inversion."""

    return bool(_DELETE_CURRENT_ONLY.fullmatch((text or "").strip()))


def is_explicit_revision_request(text: str) -> bool:
    """Recognize a control-only request to revise, never a replacement payload."""

    return bool(_REVISION_REQUEST_ONLY.fullmatch((text or "").strip()))


def is_explicit_pending_cancel(text: str) -> bool:
    """Recognize a conversational cancel that never mutates a saved event."""

    return bool(_CANCEL_PENDING_ONLY.fullmatch((text or "").strip()))


def uses_cyrillic(text: str) -> bool:
    return bool(re.search(r"[\u0400-\u04ff]", text or ""))
