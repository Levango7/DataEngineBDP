"""Mock 实体抽取器 - 基于规则匹配.

设计原则：
    - 不依赖任何外部模型，纯规则匹配，便于测试与离线场景。
    - 内置常见实体类型（Person / Organization / City / Date / Number）的规则。
    - 支持自定义规则扩展。
"""
from __future__ import annotations

import re
import uuid
from typing import Any, Callable

from knowledge_engine.interfaces.entity_extractor import EntityExtractor
from knowledge_engine.models.entity import Entity


# 规则函数签名：输入文本，返回 [(surface, start, end, props), ...]
RuleFn = Callable[[str], list[tuple[str, int, int, dict[str, Any]]]]


def _rule_chinese_person(text: str) -> list[tuple[str, int, int, dict[str, Any]]]:
    """中文姓名规则：2-3 个汉字且首字常见姓氏，排除停用字干扰."""
    surnames = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣翁荀羊於惠甄曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴郁胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍却璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公"
    # 常见停用字，不应出现在姓名中（避免"张三在北"等误匹配）
    stopwords = "在和的的是了与及或但而又也都就还只可须能会被让把向从对为以于并给叫说要想去来做过看着到进出场上下中前后左右内外里外头尾本末"
    # 姓 + 1~2 个非停用字汉字
    pattern = re.compile(
        rf"[{surnames}](?:(?![{stopwords}])[\u4e00-\u9fa5]){{1,2}}"
    )
    results: list[tuple[str, int, int, dict[str, Any]]] = []
    for m in pattern.finditer(text):
        surface = m.group()
        # 额外校验：名字内部不应包含停用字
        if any(ch in stopwords for ch in surface[1:]):
            continue
        results.append((surface, m.start(), m.end(), {}))
    return results


def _rule_organization(text: str) -> list[tuple[str, int, int, dict[str, Any]]]:
    """组织机构规则：以"公司/集团/大学/研究院/银行"结尾."""
    pattern = re.compile(
        r"[\u4e00-\u9fa5A-Za-z0-9（）()]{2,20}"
        r"(?:公司|集团|大学|研究院|银行|医院|政府|厅|局|部|委员会)"
    )
    return [(m.group(), m.start(), m.end(), {}) for m in pattern.finditer(text)]


def _rule_city(text: str) -> list[tuple[str, int, int, dict[str, Any]]]:
    """城市规则：以"市/区/县"结尾，或常见直辖市."""
    pattern = re.compile(
        r"(?:北京|上海|天津|重庆|广州|深圳|杭州|南京|成都|武汉|西安)"
        r"|[\u4e00-\u9fa5]{2,5}(?:市|区|县)"
    )
    return [(m.group(), m.start(), m.end(), {}) for m in pattern.finditer(text)]


def _rule_date(text: str) -> list[tuple[str, int, int, dict[str, Any]]]:
    """日期规则：YYYY-MM-DD / YYYY年MM月DD日 / YYYY年MM月 / YYYY年."""
    pattern = re.compile(
        r"\d{4}[-/年](?:\d{1,2}(?:[-/月]\d{1,2}日?)?)?"
    )
    return [(m.group(), m.start(), m.end(), {}) for m in pattern.finditer(text)]


def _rule_number(text: str) -> list[tuple[str, int, int, dict[str, Any]]]:
    """数字规则：整数或带小数."""
    pattern = re.compile(r"\d+(?:\.\d+)?")
    return [(m.group(), m.start(), m.end(), {}) for m in pattern.finditer(text)]


# 内置规则
BUILTIN_RULES: dict[str, RuleFn] = {
    "Person": _rule_chinese_person,
    "Organization": _rule_organization,
    "City": _rule_city,
    "Date": _rule_date,
    "Number": _rule_number,
}


class MockEntityExtractor(EntityExtractor):
    """基于规则的实体抽取器.

    Args:
        rules: 自定义规则 dict[type -> RuleFn]；与内置规则合并，同名覆盖。
        id_prefix: 生成的实体 ID 前缀。
    """

    def __init__(
        self,
        rules: dict[str, RuleFn] | None = None,
        id_prefix: str = "mock-ent",
    ) -> None:
        self.rules: dict[str, RuleFn] = {**BUILTIN_RULES, **(rules or {})}
        self.id_prefix = id_prefix

    async def extract(
        self, text: str, entity_types: list[str] | None = None
    ) -> list[Entity]:
        types = entity_types if entity_types is not None else list(self.rules.keys())
        # 去重：相同 (type, surface) 只保留首次出现
        seen: set[tuple[str, str]] = set()
        entities: list[Entity] = []
        for ent_type in types:
            rule = self.rules.get(ent_type)
            if rule is None:
                continue
            for surface, start, end, props in rule(text):
                key = (ent_type, surface)
                if key in seen:
                    continue
                seen.add(key)
                ent_id = f"{self.id_prefix}-{ent_type}-{surface}-{start}"
                entities.append(
                    Entity(
                        id=ent_id,
                        name=surface,
                        type=ent_type,
                        properties={"start": start, "end": end, **props},
                        source="mock-rule",
                        confidence=0.9,
                    )
                )
        return entities