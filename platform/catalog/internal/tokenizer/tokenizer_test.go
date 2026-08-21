package tokenizer

import (
	"math"
	"testing"
)

// TestTokenize_PureChinese_Bigram 验证纯中文 bigram 分词。
// 这是修复的核心场景：搜“订单明细”应能与“销售订单明细表”产生交集。
func TestTokenize_PureChinese_Bigram(t *testing.T) {
	got := Tokenize("销售订单明细表")
	want := []string{"销售", "售订", "订单", "单明", "明细", "细表"}
	if !equalSlice(got, want) {
		t.Fatalf("Tokenize(销售订单明细表) = %v, want %v", got, want)
	}
}

// TestTokenize_QueryChinese_Bigram 验证查询串的 bigram。
func TestTokenize_QueryChinese_Bigram(t *testing.T) {
	got := Tokenize("订单明细")
	want := []string{"订单", "单明", "明细"}
	if !equalSlice(got, want) {
		t.Fatalf("Tokenize(订单明细) = %v, want %v", got, want)
	}
}

// TestTokenize_SingleChinese 验证单字中文直接作为 token。
func TestTokenize_SingleChinese(t *testing.T) {
	got := Tokenize("表")
	want := []string{"表"}
	if !equalSlice(got, want) {
		t.Fatalf("Tokenize(表) = %v, want %v", got, want)
	}
}

// TestTokenize_TwoChinese 验证两字中文为一个 bigram。
func TestTokenize_TwoChinese(t *testing.T) {
	got := Tokenize("用户")
	want := []string{"用户"}
	if !equalSlice(got, want) {
		t.Fatalf("Tokenize(用户) = %v, want %v", got, want)
	}
}

// TestTokenize_English 验证英文按空格/下划线切分并小写化。
func TestTokenize_English(t *testing.T) {
	got := Tokenize("user_profile Total")
	want := []string{"user", "profile", "total"}
	if !equalSlice(got, want) {
		t.Fatalf("Tokenize(user_profile Total) = %v, want %v", got, want)
	}
}

// TestTokenize_MixedChineseEnglish 验证中英混合。
func TestTokenize_MixedChineseEnglish(t *testing.T) {
	got := Tokenize("用户 User 表")
	want := []string{"用户", "user", "表"}
	if !equalSlice(got, want) {
		t.Fatalf("Tokenize(用户 User 表) = %v, want %v", got, want)
	}
}

// TestTokenize_Empty 验证空串返回 nil。
func TestTokenize_Empty(t *testing.T) {
	got := Tokenize("")
	if got != nil {
		t.Fatalf("Tokenize(\"\") = %v, want nil", got)
	}
}

// TestTokenize_OnlyPunctuation 验证纯标点返回空。
func TestTokenize_OnlyPunctuation(t *testing.T) {
	got := Tokenize("  ,.;;  ")
	if len(got) != 0 {
		t.Fatalf("Tokenize(标点) = %v, want empty", got)
	}
}

// TestScore_ChineseSemanticMatch 核心修复验证：
// 搜“订单明细”应与“销售订单明细表”有较高匹配分（>0.5）。
func TestScore_ChineseSemanticMatch(t *testing.T) {
	query := Tokenize("订单明细")
	doc := Tokenize("销售订单明细表")
	score := Score(query, doc)
	// query=[订单 单明 明细]，doc 含全部 3 个，score 应为 1.0
	if math.Abs(score-1.0) > 1e-9 {
		t.Fatalf("Score(订单明细, 销售订单明细表) = %v, want 1.0", score)
	}
}

// TestScore_PartialMatch 验证部分匹配评分。
func TestScore_PartialMatch(t *testing.T) {
	query := Tokenize("订单明细")
	doc := Tokenize("销售订单") // doc=[销售 售订 订单]，命中“订单”1 个
	score := Score(query, doc)
	// query 3 个 token，命中 1 个，score = 1/3
	if math.Abs(score-(1.0/3.0)) > 1e-9 {
		t.Fatalf("Score(订单明细, 销售订单) = %v, want %v", score, 1.0/3.0)
	}
}

// TestScore_NoMatch 验证无匹配返回 0。
func TestScore_NoMatch(t *testing.T) {
	query := Tokenize("订单")
	doc := Tokenize("用户画像")
	score := Score(query, doc)
	if score != 0 {
		t.Fatalf("Score(订单, 用户画像) = %v, want 0", score)
	}
}

// TestScore_EmptyQuery 验证空查询返回 0。
func TestScore_EmptyQuery(t *testing.T) {
	score := Score(nil, Tokenize("用户"))
	if score != 0 {
		t.Fatalf("Score(nil, doc) = %v, want 0", score)
	}
}

// TestMatch_HitAndMiss 验证 Match 命中/未命中。
func TestMatch_HitAndMiss(t *testing.T) {
	if !Match(Tokenize("画像"), Tokenize("用户画像表")) {
		t.Fatal("Match(画像, 用户画像表) should be true")
	}
	if Match(Tokenize("订单"), Tokenize("用户画像")) {
		t.Fatal("Match(订单, 用户画像) should be false")
	}
	if Match(nil, Tokenize("用户")) {
		t.Fatal("Match(nil, doc) should be false")
	}
}

// TestTokenize_Katakana 验证日文假名走 CJK 路径（不报错且产生 token）。
func TestTokenize_Katakana(t *testing.T) {
	got := Tokenize("テーブル")
	if len(got) == 0 {
		t.Fatal("Tokenize(テーブル) should produce tokens")
	}
}

// equalSlice 比较两个字符串切片是否完全相等（顺序敏感）。
func equalSlice(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}