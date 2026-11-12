# Qbotics' changes to litehtml, applied to the pinned source on every build (see build-inside.sh).
# Each change must find its anchor exactly once, or the build stops: litehtml moved on, and the
# patch needs looking at.
#
#  1. Pages are always UTF-8: the charset sniffing (the one place litehtml throws a C++
#     exception) is cut out, so the engine builds without exceptions.
#  2. gap, row-gap and column-gap for flex containers.
#  3. CSS grid (display: grid / inline-grid, grid-template-columns, grid-column): a grid is a
#     flex container inside, laid out by render_item_grid (extra/render_grid.cpp).
import io
import re
import sys

ROOT = sys.argv[1]


def edit(path, pairs):
    path = ROOT + "/" + path
    text = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        if callable(old):
            text = old(text)
            continue
        assert text.count(old) == 1, "patch anchor not found once in %s: %r" % (path, old[:80])
        text = text.replace(old, new)
    io.open(path, "w", encoding="utf-8").write(text)


# ---- 1. no exceptions ----------------------------------------------------------------------
def no_prescan(text):
    text = text.replace("throw abort_prescan_exception(); // abort prescan", "__builtin_trap();")
    text, n = re.subn(r"try\s*\{\s*return prescan_a_byte_stream_to_determine_its_encoding\(str\);\s*\}\s*catch\(abort_prescan_exception&\)\s*\{\s*return get_xml_encoding\(str\);\s*\}",
                      "(void)str; return encoding::utf_8;", text)
    assert n == 1 and "throw " not in text.replace("// abort prescan", ""), "the encodings patch no longer fits litehtml"
    return text


edit("src/encodings.cpp", [(no_prescan, None)])

# ---- the new properties ----------------------------------------------------------------------
edit("include/litehtml/string_id.h", [
    ("_align_self_, _flex_grow_, _flex_shrink_, _flex_basis_,",
     "_align_self_, _flex_grow_, _flex_shrink_, _flex_basis_, _gap_, _row_gap_, _column_gap_, _grid_template_columns_, _grid_column_,"),
])
edit("src/style.cpp", [
    ("        {_flex_flow_,       {_flex_direction_, _flex_wrap_}                                                           },",
     "        {_flex_flow_,       {_flex_direction_, _flex_wrap_}                                                           },\n"
     "        {_gap_,             {_row_gap_, _column_gap_}                                                                 },"),
    ("        case _flex_flow_:\n            parse_flex_flow(value, important);\n            break;",
     "        case _flex_flow_:\n            parse_flex_flow(value, important);\n            break;\n\n"
     "        case _row_gap_:\n        case _column_gap_:\n"
     "            add_length_property(name, val, {}, f_length_percentage | f_positive, important);\n            break;\n\n"
     "        case _gap_:\n"
     "            if(parse_two_lengths(value, len, f_length_percentage | f_positive))\n            {\n"
     "                add_parsed_property(_row_gap_, property_value(len[0], important));\n"
     "                add_parsed_property(_column_gap_, property_value(len[1], important));\n            }\n            break;\n\n"
     "        case _grid_template_columns_:\n        case _grid_column_:\n"
     "            str = get_repr(value, 0, -1, true);\n            add_parsed_property(name, property_value(str, important));\n            break;"),
])
edit("include/litehtml/css_values.h", [
    ('''    inline constexpr auto style_display_strings = split_css_values<18>(
        "none;block;inline;inline-block;inline-table;list-item;table;table-caption;table-cell;table-column;"
        "table-column-group;table-footer-group;table-header-group;table-row;table-row-group;inline-text;flex;"
        "inline-flex");''', '''    inline constexpr auto style_display_strings = split_css_values<20>(
        "none;block;inline;inline-block;inline-table;list-item;table;table-caption;table-cell;table-column;"
        "table-column-group;table-footer-group;table-header-group;table-row;table-row-group;inline-text;flex;"
        "inline-flex;grid;inline-grid");'''),
    ("        display_flex,\n        display_inline_flex,\n    };", "        display_flex,\n        display_inline_flex,\n        display_grid,\n        display_inline_grid,\n    };"),
])
edit("include/litehtml/css_properties.h", [
    ("        css_length           m_flex_basis;",
     "        css_length           m_flex_basis;\n        css_length           m_row_gap;\n        css_length           m_column_gap;\n"
     "        bool                 m_grid = false;\n        std::string          m_grid_template_columns;\n        std::string          m_grid_column;"),
    ("        const css_length&    get_flex_basis() const;",
     "        const css_length&    get_flex_basis() const;\n        const css_length&    get_row_gap() const { return m_row_gap; }\n"
     "        const css_length&    get_column_gap() const { return m_column_gap; }\n        bool                 is_grid() const { return m_grid; }\n"
     "        const std::string&   get_grid_template_columns() const { return m_grid_template_columns; }\n"
     "        const std::string&   get_grid_column() const { return m_grid_column; }"),
])
edit("src/css_properties.cpp", [
    ("    m_display = static_cast<style_display>(el->get_property<int>(_display_, false, display_inline, offset(m_display)));",
     "    m_display = static_cast<style_display>(el->get_property<int>(_display_, false, display_inline, offset(m_display)));\n"
     "    // A grid is a flex container inside (so its children are flex items), laid out as a grid.\n"
     "    m_grid = m_display == display_grid || m_display == display_inline_grid;\n"
     "    if(m_display == display_grid)\n    {\n        m_display = display_flex;\n    } else if(m_display == display_inline_grid)\n    {\n        m_display = display_inline_flex;\n    }"),
    ("    if(m_display == display_flex || m_display == display_inline_flex)\n    {\n        m_flex_direction =",
     "    if(m_display == display_flex || m_display == display_inline_flex)\n    {\n"
     "        m_row_gap = el->get_property<css_length>(_row_gap_, false, css_length(0), offset(m_row_gap));\n"
     "        doc->cvt_units(m_row_gap, m_font_metrics, 0_px);\n"
     "        m_column_gap = el->get_property<css_length>(_column_gap_, false, css_length(0), offset(m_column_gap));\n"
     "        doc->cvt_units(m_column_gap, m_font_metrics, 0_px);\n"
     "        m_grid_template_columns = el->get_property<std::string>(_grid_template_columns_, false, \"\", offset(m_grid_template_columns));\n"
     "        m_flex_direction ="),
    ("    auto parent = el->parent();\n    if(parent && (parent->css().m_display == display_flex || parent->css().m_display == display_inline_flex))\n    {",
     "    auto parent = el->parent();\n    if(parent && (parent->css().m_display == display_flex || parent->css().m_display == display_inline_flex))\n    {\n"
     "        m_grid_column = el->get_property<std::string>(_grid_column_, false, \"\", offset(m_grid_column));"),
])
edit("src/element.cpp", [
    ('#include "render_flex.h"', '#include "render_flex.h"\n#include "render_grid.h"'),
    ("            ret = std::make_shared<render_item_flex>(shared_from_this());",
     "            if(css().is_grid())\n            {\n                ret = std::make_shared<render_item_grid>(shared_from_this());\n            } else\n            {\n"
     "                ret = std::make_shared<render_item_flex>(shared_from_this());\n            }"),
])

# ---- 2. gap in flex layout -------------------------------------------------------------------
edit("include/litehtml/render_flex.h", [
    ("                                       bool is_row_direction, pixel_t container_main_size, bool single_line);",
     "                                       bool is_row_direction, pixel_t container_main_size, bool single_line,\n"
     "                                       pixel_t main_gap = 0_px);"),
])
edit("src/render_flex.cpp", [
    # The gaps along and across the main axis.
    ("    bool single_line   = css().get_flex_wrap() == flex_wrap_nowrap;\n    bool fit_container = false;",
     "    bool single_line   = css().get_flex_wrap() == flex_wrap_nowrap;\n    bool fit_container = false;\n"
     "    pixel_t row_gap    = css().get_row_gap().is_predefined() ? 0_px : css().get_row_gap().calc_percent(self_size.render_width);\n"
     "    pixel_t column_gap = css().get_column_gap().is_predefined() ? 0_px : css().get_column_gap().calc_percent(self_size.render_width);\n"
     "    pixel_t main_gap   = is_row_direction ? column_gap : row_gap;\n"
     "    pixel_t cross_gap  = is_row_direction ? row_gap : column_gap;"),
    ("    m_lines = get_lines(self_size, fmt_ctx, is_row_direction, container_main_size, single_line);",
     "    m_lines = get_lines(self_size, fmt_ctx, is_row_direction, container_main_size, single_line, main_gap);"),
    # Each line is laid out as if narrower by its gaps.
    ("        ln.init(container_main_size, fit_container, is_row_direction, self_size, fmt_ctx);\n        sum_cross_size += ln.cross_size;",
     "        pixel_t line_gaps = main_gap * pixel_t(static_cast<int>(ln.items.size()) - 1);\n"
     "        ln.init(container_main_size - line_gaps, fit_container, is_row_direction, self_size, fmt_ctx);\n"
     "        sum_cross_size += ln.cross_size;"),
    ("    pixel_t free_cross_size = 0_px;\n    bool    is_wrap_reverse",
     "    if(!m_lines.empty())\n    {\n        sum_cross_size += cross_gap * pixel_t(static_cast<int>(m_lines.size()) - 1);\n    }\n"
     "    pixel_t free_cross_size = 0_px;\n    bool    is_wrap_reverse"),
    ("        ln.cross_start  = line_pos;\n        line_pos       += ln.cross_size + add_after_line;",
     "        ln.cross_start  = line_pos;\n        line_pos       += ln.cross_size + add_after_line + cross_gap;"),
    # ...then each item moves along by its share of the gaps.
    ("        pixel_t height =\n            ln.calculate_items_position(container_main_size, justify_content, is_row_direction, self_size, fmt_ctx);\n        m_pos.height = std::max(m_pos.height, height);",
     "        pixel_t line_gaps = main_gap * pixel_t(static_cast<int>(ln.items.size()) - 1);\n"
     "        pixel_t height =\n            ln.calculate_items_position(container_main_size - line_gaps, justify_content, is_row_direction, self_size, fmt_ctx);\n"
     "        if(main_gap != 0_px)\n        {\n            int index = 0;\n            for(auto& item : ln.items)\n            {\n"
     "                if(is_row_direction)\n                {\n                    item->el->pos().x += main_gap * pixel_t(index);\n                } else\n                {\n"
     "                    item->el->pos().y += main_gap * pixel_t(index);\n                }\n                index++;\n            }\n"
     "            if(!is_row_direction)\n            {\n                height += line_gaps;\n            }\n        }\n"
     "        m_pos.height = std::max(m_pos.height, height);"),
    # Lines wrap counting the gaps.
    ("    pixel_t container_main_size, bool single_line)\n{",
     "    pixel_t container_main_size, bool single_line, pixel_t main_gap)\n{"),
    ("        if(!line.items.empty() && !single_line && line.main_size + item->main_size > container_main_size)",
     "        if(!line.items.empty() && !single_line &&\n           line.main_size + item->main_size + main_gap * pixel_t(static_cast<int>(line.items.size())) > container_main_size)"),
])
print("patched")
