// Package tokenizer 提供中文 + 英文混合文本的分词能力。
//
// 设计目标：
//   - 纯 Go 实现，无 CGO 依赖，跨平台编译零配置
//   - 中文采用 bigram（二元）分词：对连续中文段提取所有相邻 2 字组合作为 token
//     这样搜“订单明细”会生成 [订单 单明 明细]，能匹配“销售订单明细表”中的
//     [订单 单明 明细]，实现语义级召回（与 Elasticsearch IK 分词等价语义）
//   - 英文/数字按空格与标点切分，并统一小写
//   - 单字中文段（长度 1）直接作为 token
//
// 该分词器用于资产目录全文检索，解决“搜中文子串命中不准”的问题。
package tokenizer

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// Tokenize 将输入文本切分为 token 列表。
//
// 规则：
//  1. 按字符遍历，将文本划分为“中文段”与“非中文段（英文/数字/标点）”交替的子串
//  2. 中文段：提取所有相邻 2 字 bigram；若段长为 1，则该单字作为一个 token
//  3. 非中文段：按空格与标点切分，每个连续字母数字子串作为一个 token（小写化）
//  4. 空 token 被丢弃
//
// 示例：
//
//	Tokenize("销售订单明细表") => [销售 售订 订单 单明 明细 细表]
//	Tokenize("订单明细")       => [订单 单明 明细]
//	Tokenize("user_profile")   => [user profile]
//	Tokenize("用户 User 表")   => [用户 user 表]
func Tokenize(text string) []string {
	if text == "" {
		return nil
	}

	var tokens []string
	var cjkBuf strings.Builder
	var latinBuf strings.Builder

	flushCJK := func() {
		if cjkBuf.Len() == 0 {
			return
		}
		s := cjkBuf.String()
		cjkBuf.Reset()
		tokens = append(tokens, cjkBigrams(s)...)
	}
	flushLatin := func() {
		if latinBuf.Len() == 0 {
			return
		}
		s := latinBuf.String()
		latinBuf.Reset()
		// 按非字母数字字符切分，小写化
		for _, field := range strings.FieldsFunc(s, isNonTokenRune) {
			if field != "" {
				tokens = append(tokens, strings.ToLower(field))
			}
		}
	}

	for _, r := range text {
		if isCJK(r) {
			flushLatin()
			cjkBuf.WriteRune(r)
		} else {
			flushCJK()
			latinBuf.WriteRune(r)
		}
	}
	flushLatin()
	flushCJK()

	return tokens
}

// cjkBigrams 对纯中文串提取 bigram；单字串返回该字本身。
func cjkBigrams(s string) []string {
	runes := []rune(s)
	n := len(runes)
	if n == 0 {
		return nil
	}
	if n == 1 {
		return []string{string(runes[0])}
	}
	out := make([]string, 0, n-1)
	for i := 0; i+1 < n; i++ {
		out = append(out, string(runes[i:i+2]))
	}
	return out
}

// isCJK 判断 r 是否为 CJK 统一表意文字（含扩展 A 区常见范围）。
// 范围参考 Unicode CJK Unified Ideographs。
func isCJK(r rune) bool {
	switch {
	case 0x4E00 <= r && r <= 0x9FFF: // CJK Unified Ideographs
		return true
	case 0x3400 <= r && r <= 0x4DBF: // CJK Extension A
		return true
	case 0xF900 <= r && r <= 0xFAFF: // CJK Compatibility Ideographs
		return true
	case 0x3040 <= r && r <= 0x30FF: // Hiragana / Katakana
		return true
	}
	return false
}

// isNonTokenRune 判断 r 是否为“非 token 字符”（用于英文段切分边界）。
// 空格、控制字符、常见标点（中英文）都视为切分边界。
func isNonTokenRune(r rune) bool {
	if unicode.IsSpace(r) {
		return true
	}
	if !unicode.IsLetter(r) && !unicode.IsDigit(r) {
		return true
	}
	return false
}

// Score 计算查询 tokens 与文档 tokens 的匹配分。
//
// 评分规则：
//   - 对 query 中每个 token，若在 doc 中出现，则计 1 分（多次出现只计一次，避免长文档刷分）
//   - 最终得分为命中 token 数 / query token 数（0~1），query 为空时返回 0
//
// 该评分用于对检索结果按相关性排序。
func Score(query, doc []string) float64 {
	if len(query) == 0 {
		return 0
	}
	docSet := make(map[string]struct{}, len(doc))
	for _, t := range doc {
		docSet[t] = struct{}{}
	}
	hit := 0
	for _, t := range query {
		if _, ok := docSet[t]; ok {
			hit++
		}
	}
	return float64(hit) / float64(len(query))
}

// Match 判断 query tokens 是否在 doc tokens 中命中（Score > 0）。
func Match(query, doc []string) bool {
	if len(query) == 0 {
		return false
	}
	docSet := make(map[string]struct{}, len(doc))
	for _, t := range doc {
		docSet[t] = struct{}{}
	}
	for _, t := range query {
		if _, ok := docSet[t]; ok {
			return true
		}
	}
	return false
}

// _ 确保 utf8 包被引用（保留以备后续扩展 rune 边界处理）。
var _ = utf8.RuneError