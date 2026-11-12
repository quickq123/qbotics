// Qbotics' addition to litehtml: CSS grid, the part pages use most.
//
//  - grid-template-columns: lengths (px, em, rem, %), fr, auto, minmax(a, b), and
//    repeat(n | auto-fill | auto-fit, tracks). Rows are as tall as their tallest item.
//  - Items are placed row by row; grid-column: span n, n, n / m, n / span m, n / -1.
//  - gap, row-gap, column-gap.
//  - Items stretch to their row's height (as align-items: stretch, the default), unless
//    they have a height of their own.
// Not here: grid-template-rows and -areas, grid-row, named lines, dense packing, alignment.

#include "render_grid.h"
#include "html_tag.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <string>
#include <vector>

namespace
{
    using litehtml::pixel_t;

    struct track
    {
        float fixed = -1; // a size, or -1 if flexible
        float fr    = 0;  // flexible share
        float min   = 0;  // at least this (minmax)
    };

    std::string trim(const std::string& s)
    {
        size_t a = s.find_first_not_of(" \t\n\r");
        size_t b = s.find_last_not_of(" \t\n\r");
        return a == std::string::npos ? "" : s.substr(a, b - a + 1);
    }

    // Splits at a separator outside parentheses.
    std::vector<std::string> split_top(const std::string& s, char sep)
    {
        std::vector<std::string> out;
        int                      depth = 0;
        std::string              cur;
        for (char c : s)
        {
            if (c == '(') depth++;
            if (c == ')') depth--;
            bool split = depth == 0 && (sep == ' ' ? (c == ' ' || c == '\t' || c == '\n') : c == sep);
            if (split)
            {
                if (!trim(cur).empty()) out.push_back(trim(cur));
                cur.clear();
            } else
            {
                cur += c;
            }
        }
        if (!trim(cur).empty()) out.push_back(trim(cur));
        return out;
    }

    // Glues "repeat (" back to "repeat(" and drops spaces next to brackets, as the CSS parser may space them.
    std::string tidy(const std::string& s)
    {
        std::string out;
        for (size_t i = 0; i < s.size(); i++)
        {
            char c = s[i];
            if (c == ' ' && !out.empty() && (out.back() == '(' || out.back() == ',' || out.back() == ' ')) continue;
            if ((c == '(' || c == ')' || c == ',') && !out.empty() && out.back() == ' ') out.pop_back();
            out += c;
        }
        return out;
    }

    bool ends_with(const std::string& s, const char* tail)
    {
        size_t n = std::strlen(tail);
        return s.size() >= n && s.compare(s.size() - n, n, tail) == 0;
    }

    // A length in pixels, or -1 if it isn't one.
    float length(const std::string& t, float width, float font)
    {
        if (t.empty()) return -1;
        char* end = nullptr;
        float v   = std::strtof(t.c_str(), &end);
        if (end == t.c_str()) return -1;
        std::string unit = end;
        if (unit.empty() || unit == "px") return v;
        if (unit == "%") return v * width / 100.0f;
        if (unit == "em") return v * font;
        if (unit == "rem") return v * 16.0f;
        return -1;
    }

    bool parse_track(const std::string& t, float width, float font, track& out)
    {
        if (t == "auto" || t == "min-content" || t == "max-content")
        {
            out = {-1, 1, 0};
            return true;
        }
        if (ends_with(t, "fr"))
        {
            out = {-1, std::max(0.0f, std::strtof(t.c_str(), nullptr)), 0};
            return true;
        }
        if (t.rfind("minmax(", 0) == 0 && t.back() == ')')
        {
            auto args = split_top(t.substr(7, t.size() - 8), ',');
            if (args.size() != 2) return false;
            float lo = std::max(0.0f, length(args[0], width, font));
            if (ends_with(args[1], "fr") || args[1] == "auto")
            {
                float fr = args[1] == "auto" ? 1.0f : std::strtof(args[1].c_str(), nullptr);
                out      = {-1, std::max(0.0f, fr), lo};
            } else
            {
                out = {std::max(lo, length(args[1], width, font)), 0, 0};
            }
            return true;
        }
        float v = length(t, width, font);
        if (v < 0) return false;
        out = {v, 0, 0};
        return true;
    }

    std::vector<track> parse_tracks(const std::string& tpl, float width, float gap, float font)
    {
        std::vector<track> out;
        for (const auto& tok : split_top(tidy(tpl), ' '))
        {
            if (tok.rfind("repeat(", 0) == 0 && tok.back() == ')')
            {
                std::string inner = tok.substr(7, tok.size() - 8);
                size_t      comma = std::string::npos;
                int         depth = 0;
                for (size_t i = 0; i < inner.size(); i++)
                {
                    if (inner[i] == '(') depth++;
                    if (inner[i] == ')') depth--;
                    if (inner[i] == ',' && depth == 0)
                    {
                        comma = i;
                        break;
                    }
                }
                if (comma == std::string::npos) continue;
                std::string        count = trim(inner.substr(0, comma));
                std::vector<track> once;
                for (const auto& t : split_top(inner.substr(comma + 1), ' '))
                {
                    track tr;
                    if (parse_track(t, width, font, tr)) once.push_back(tr);
                }
                if (once.empty()) continue;
                int n;
                if (count == "auto-fill" || count == "auto-fit")
                {
                    // As many as fit, each at least its fixed size or its minmax minimum.
                    float each = 0;
                    for (const auto& t : once) each += (t.fixed >= 0 ? t.fixed : t.min) + gap;
                    n = each <= gap * once.size() ? 1 : std::max(1, (int) std::floor((width + gap) / each));
                } else
                {
                    n = std::clamp(std::atoi(count.c_str()), 1, 64);
                }
                for (int i = 0; i < n; i++) out.insert(out.end(), once.begin(), once.end());
            } else
            {
                track tr;
                if (parse_track(tok, width, font, tr)) out.push_back(tr);
            }
        }
        if (out.empty()) out.push_back({-1, 1, 0});
        if (out.size() > 64) out.resize(64);
        return out;
    }

    std::vector<float> size_tracks(const std::vector<track>& tracks, float width, float gap)
    {
        float fixed = 0, fr = 0;
        for (const auto& t : tracks)
        {
            if (t.fixed >= 0) fixed += t.fixed;
            else fr += t.fr;
        }
        float free = width - fixed - gap * (float) (tracks.size() - 1);
        float unit = fr > 0 ? std::max(0.0f, free) / fr : 0;
        std::vector<float> sizes;
        for (const auto& t : tracks) sizes.push_back(t.fixed >= 0 ? t.fixed : std::max(t.min, t.fr * unit));
        return sizes;
    }

    // grid-column: "span 2", "2", "1 / 3", "1 / span 2", "1 / -1". Gives start (-1: next free) and span.
    void parse_column(const std::string& raw, int columns, int& start, int& span)
    {
        start = -1;
        span  = 1;
        std::string s = trim(raw);
        if (s.empty() || s == "auto") return;
        auto parts = split_top(s, '/');
        auto one   = [](const std::string& t, bool& is_span) {
            std::string v = trim(t);
            is_span       = v.rfind("span", 0) == 0;
            if (is_span) v = trim(v.substr(4));
            return std::atoi(v.c_str());
        };
        bool a_span = false, b_span = false;
        int  a      = one(parts[0], a_span);
        if (a_span)
        {
            span = std::max(1, a);
            return;
        }
        if (a > 0) start = a - 1;
        if (parts.size() > 1)
        {
            int b = one(parts[1], b_span);
            if (b_span) span = std::max(1, b);
            else if (b < 0) span = std::max(1, columns + 1 + b + 1 - a);
            else if (b > a) span = b - a;
        }
    }
} // namespace

litehtml::pixel_t litehtml::render_item_grid::_render_content(pixel_t x, pixel_t y, bool /*second_pass*/,
                                                              const containing_block_context& self_size,
                                                              formatting_context*             fmt_ctx)
{
    float width = (float) pixel_t(self_size.render_width);
    float font  = css().get_font_size();
    float cgap  = css().get_column_gap().is_predefined() ? 0.0f : (float) css().get_column_gap().calc_percent(self_size.render_width);
    float rgap  = css().get_row_gap().is_predefined() ? 0.0f : (float) css().get_row_gap().calc_percent(self_size.render_width);

    std::vector<float> columns = size_tracks(parse_tracks(css().get_grid_template_columns(), width, cgap, font), width, cgap);
    int                ncols   = (int) columns.size();
    std::vector<float> colx(ncols, 0);
    for (int i = 1; i < ncols; i++) colx[i] = colx[i - 1] + columns[i - 1] + cgap;

    struct placed
    {
        std::shared_ptr<render_item> el;
        int                          row, col, span;
    };
    std::vector<placed> items;
    int                 row = 0, col = 0;
    for (auto& el : m_children)
    {
        if (el->src_el()->css().get_display() == display_none) continue;
        int start, span;
        parse_column(el->src_el()->css().get_grid_column(), ncols, start, span);
        span = std::clamp(span, 1, ncols);
        if (start >= 0)
        {
            start = std::min(start, ncols - span);
            if (start < col) row++;
            col = start;
        } else if (col + span > ncols)
        {
            row++;
            col = 0;
        }
        items.push_back({el, row, col, span});
        col += span;
        if (col >= ncols)
        {
            row++;
            col = 0;
        }
    }
    int rows = items.empty() ? 0 : items.back().row + 1;

    // Each item at its columns' width; each row as tall as its tallest item.
    std::vector<float> heights(rows, 0);
    for (auto& p : items)
    {
        float w = colx[p.col + p.span - 1] + columns[p.col + p.span - 1] - colx[p.col];
        p.el->render(0_px, 0_px,
                     self_size.new_width(pixel_t(w) - p.el->render_offset_width(), containing_block_context::size_mode_exact_width),
                     fmt_ctx, false);
        p.el->pos().width = pixel_t(w) - p.el->content_offset_width();
        heights[p.row]    = std::max(heights[p.row], (float) p.el->height());
    }
    std::vector<float> rowy(rows, 0);
    for (int i = 1; i < rows; i++) rowy[i] = rowy[i - 1] + heights[i - 1] + rgap;

    for (auto& p : items)
    {
        if (p.el->css().get_height().is_predefined())
        {
            // Stretched to the row, as align-items: stretch.
            p.el->render(0_px, 0_px,
                         self_size.new_width_height(p.el->pos().width + p.el->box_sizing_width(),
                                                    pixel_t(heights[p.row]) - p.el->content_offset_height() + p.el->box_sizing_height(),
                                                    containing_block_context::size_mode_exact_width |
                                                        containing_block_context::size_mode_exact_height),
                         fmt_ctx);
        }
        p.el->pos().x = pixel_t(colx[p.col]) + p.el->content_offset_left();
        p.el->pos().y = pixel_t(rowy[p.row]) + p.el->content_offset_top();
    }

    float total = rows ? rowy[rows - 1] + heights[rows - 1] : 0;
    m_pos.height = std::max(m_pos.height, pixel_t(total));
    m_pos.move_to(x, y);
    m_pos.x += content_offset_left();
    m_pos.y += content_offset_top();
    m_pos.width = self_size.render_width;
    return self_size.render_width;
}
