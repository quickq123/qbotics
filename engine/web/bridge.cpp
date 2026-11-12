// The web panel engine (design doc §10.3): litehtml lays out a page; the game draws it.
//
// The game gives this module a page (HTML, plus CSS of its own), a size, and answers about
// fonts and images through four imports. The module lays the page out and returns a display
// list: a compact list of drawing steps (fills, borders, text, gradients, clips) that the game
// paints with Java 2D. Pointer events go the other way: the module tells the game which element
// a point is on, and whether :hover or :active changed what is drawn.
//
// Built to WebAssembly with wasi-sdk (see build.sh), run by Chicory in each player's game.

#include <litehtml.h>
#include <litehtml/render_item.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#ifdef __wasm__
#define IMPORT(name) __attribute__((import_module("web"), import_name(name)))
#define EXPORT(name) extern "C" __attribute__((export_name(name)))
#else
#define IMPORT(name)
#define EXPORT(name) extern "C"
#endif

// ---- what the game answers ----------------------------------------------------------------
extern "C" {
// A font for a family, size (px), weight (100-900) and italic; returns its number.
IMPORT("font_create") int32_t host_font_create(const char* family, int32_t family_len, float size, int32_t weight, int32_t italic);
// Fills six floats: size, height, ascent, descent, x-height, width of "0".
IMPORT("font_metrics") void host_font_metrics(int32_t font, float* out);
IMPORT("text_width") float host_text_width(int32_t font, const char* text, int32_t len);
// Fills two floats: an image's width and height (0 0 if unknown).
IMPORT("image_size") void host_image_size(const char* url, int32_t len, float* out);
}

// ---- the display list ----------------------------------------------------------------------
enum Op : uint8_t {
    OP_FILL = 1,     // box, radii, colour
    OP_TEXT = 2,     // box, font, colour, decoration, text
    OP_BORDERS = 3,  // box, radii, 4 x (width, style, colour)
    OP_MARKER = 4,   // box, type, colour
    OP_IMAGE = 5,    // box (border), clip box, radii, url
    OP_LINEAR = 6,   // box, radii, x0 y0 x1 y1, stops
    OP_RADIAL = 7,   // box, radii, cx cy rx ry, stops
    OP_CLIP = 8,     // box, radii
    OP_UNCLIP = 9,
};

static std::vector<uint8_t> list;

static void put_u8(uint8_t v) { list.push_back(v); }
static void put_i32(int32_t v) {
    uint8_t b[4];
    std::memcpy(b, &v, 4);
    list.insert(list.end(), b, b + 4);
}
static void put_f(float v) {
    uint8_t b[4];
    std::memcpy(b, &v, 4);
    list.insert(list.end(), b, b + 4);
}
static void put_str(const std::string& s) {
    put_i32((int32_t)s.size());
    list.insert(list.end(), s.begin(), s.end());
}
static void put_box(const litehtml::position& p) {
    put_f(p.x);
    put_f(p.y);
    put_f(p.width);
    put_f(p.height);
}
static void put_radii(const litehtml::border_radiuses& r) {
    put_f(r.top_left_x); put_f(r.top_left_y);
    put_f(r.top_right_x); put_f(r.top_right_y);
    put_f(r.bottom_right_x); put_f(r.bottom_right_y);
    put_f(r.bottom_left_x); put_f(r.bottom_left_y);
}
static void put_color(const litehtml::web_color& c) {
    put_i32((int32_t)(((uint32_t)c.alpha << 24) | ((uint32_t)c.red << 16) | ((uint32_t)c.green << 8) | (uint32_t)c.blue));
}
static void put_stops(const litehtml::background_layer::gradient_base& g) {
    put_i32((int32_t)g.color_points.size());
    for (const auto& p : g.color_points) {
        put_f(p.offset);
        put_color(p.color);
    }
}

// ---- the container: litehtml's view of the game ---------------------------------------------
struct Font {
    int32_t host;
    int decoration;
};

class Container : public litehtml::document_container {
  public:
    float width = 300;
    float height = 200;
    std::vector<Font> fonts;

    litehtml::uint_ptr create_font(const litehtml::font_description& d, const litehtml::document*, litehtml::font_metrics* fm) override {
        int32_t host = host_font_create(d.family.c_str(), (int32_t)d.family.size(), (float)d.size, d.weight,
                                        d.style == litehtml::font_style_italic ? 1 : 0);
        float m[6] = {0};
        host_font_metrics(host, m);
        if (fm) {
            fm->font_size = m[0];
            fm->height = m[1];
            fm->ascent = m[2];
            fm->descent = m[3];
            fm->x_height = m[4];
            fm->ch_width = m[5];
            fm->draw_spaces = d.decoration_line != litehtml::text_decoration_line_none;
            fm->sub_shift = m[0] / 5;
            fm->super_shift = m[0] / 3;
        }
        fonts.push_back({host, d.decoration_line});
        return (litehtml::uint_ptr)fonts.size(); // 1-based, so 0 is "no font"
    }
    void delete_font(litehtml::uint_ptr) override {}

    litehtml::pixel_t text_width(const char* text, litehtml::uint_ptr font) override {
        if (font == 0 || font > fonts.size()) {
            return 0;
        }
        return host_text_width(fonts[font - 1].host, text, (int32_t)std::strlen(text));
    }

    void draw_text(litehtml::uint_ptr, const char* text, litehtml::uint_ptr font, litehtml::web_color color,
                   const litehtml::position& pos) override {
        if (font == 0 || font > fonts.size()) {
            return;
        }
        put_u8(OP_TEXT);
        put_box(pos);
        put_i32(fonts[font - 1].host);
        put_color(color);
        put_i32(fonts[font - 1].decoration);
        put_str(text);
    }

    litehtml::pixel_t pt_to_px(float pt) const override { return pt * 96.0f / 72.0f; }
    litehtml::pixel_t get_default_font_size() const override { return 16; }
    const char* get_default_font_name() const override { return "sans-serif"; }

    void draw_list_marker(litehtml::uint_ptr, const litehtml::list_marker& marker) override {
        put_u8(OP_MARKER);
        put_box(marker.pos);
        put_i32((int32_t)marker.marker_type);
        put_color(marker.color);
    }

    void load_image(const char*, const char*, bool) override {}
    void get_image_size(const char* src, const char*, litehtml::size& sz) override {
        float out[2] = {0, 0};
        host_image_size(src, (int32_t)std::strlen(src), out);
        sz.width = out[0];
        sz.height = out[1];
    }
    void draw_image(litehtml::uint_ptr, const litehtml::background_layer& layer, const std::string& url, const std::string&) override {
        put_u8(OP_IMAGE);
        put_box(layer.border_box);
        put_box(layer.clip_box);
        put_radii(layer.border_radius);
        put_str(url);
    }
    void draw_solid_fill(litehtml::uint_ptr, const litehtml::background_layer& layer, const litehtml::web_color& color) override {
        put_u8(OP_FILL);
        put_box(layer.border_box);
        put_radii(layer.border_radius);
        put_color(color);
    }
    void draw_linear_gradient(litehtml::uint_ptr, const litehtml::background_layer& layer,
                              const litehtml::background_layer::linear_gradient& g) override {
        put_u8(OP_LINEAR);
        put_box(layer.border_box);
        put_radii(layer.border_radius);
        put_f(g.start.x); put_f(g.start.y); put_f(g.end.x); put_f(g.end.y);
        put_stops(g);
    }
    void draw_radial_gradient(litehtml::uint_ptr, const litehtml::background_layer& layer,
                              const litehtml::background_layer::radial_gradient& g) override {
        put_u8(OP_RADIAL);
        put_box(layer.border_box);
        put_radii(layer.border_radius);
        put_f(g.position.x); put_f(g.position.y); put_f(g.radius.x); put_f(g.radius.y);
        put_stops(g);
    }
    void draw_conic_gradient(litehtml::uint_ptr, const litehtml::background_layer& layer,
                             const litehtml::background_layer::conic_gradient& g) override {
        // Not drawn as a cone yet: its first colour fills the box.
        if (!g.color_points.empty()) {
            draw_solid_fill(0, layer, g.color_points.front().color);
        }
    }
    void draw_borders(litehtml::uint_ptr, const litehtml::borders& b, const litehtml::position& pos, bool) override {
        put_u8(OP_BORDERS);
        put_box(pos);
        put_radii(b.radius);
        for (const litehtml::border* side : {&b.left, &b.top, &b.right, &b.bottom}) {
            put_f(side->width);
            put_i32((int32_t)side->style);
            put_color(side->color);
        }
    }

    void set_caption(const char*) override {}
    void set_base_url(const char*) override {}
    void link(const std::shared_ptr<litehtml::document>&, const litehtml::element::ptr&) override {}
    void on_anchor_click(const char*, const litehtml::element::ptr&) override {}
    void on_mouse_event(const litehtml::element::ptr&, litehtml::mouse_event) override {}
    void set_cursor(const char*) override {}
    void transform_text(std::string& text, litehtml::text_transform tt) override {
        for (char& c : text) {
            if (tt == litehtml::text_transform_uppercase && c >= 'a' && c <= 'z') c -= 32;
            if (tt == litehtml::text_transform_lowercase && c >= 'A' && c <= 'Z') c += 32;
        }
    }
    void import_css(std::string&, const std::string&, std::string&) override {}  // pages bring their CSS inline
    void set_clip(const litehtml::position& pos, const litehtml::border_radiuses& r) override {
        put_u8(OP_CLIP);
        put_box(pos);
        put_radii(r);
    }
    void del_clip() override { put_u8(OP_UNCLIP); }
    void get_viewport(litehtml::position& viewport) const override {
        viewport.x = 0;
        viewport.y = 0;
        viewport.width = width;
        viewport.height = height;
    }
    litehtml::element::ptr create_element(const char*, const litehtml::string_map&, const std::shared_ptr<litehtml::document>&) override {
        return nullptr;
    }
    void get_media_features(litehtml::media_features& media) const override {
        media.type = litehtml::media_type_screen;
        media.width = width;
        media.height = height;
        media.device_width = width;
        media.device_height = height;
        media.color = 8;
        media.monochrome = 0;
        media.color_index = 256;
        media.resolution = 96;
    }
    void get_language(std::string& language, std::string& culture) const override {
        language = "en";
        culture = "";
    }
};

static Container container;
static litehtml::document::ptr doc;
static std::string scratch;

// ---- what the game calls ------------------------------------------------------------------
EXPORT("web_alloc") void* web_alloc(int32_t n) { return std::malloc((size_t)n); }
EXPORT("web_free") void web_free(void* p) { std::free(p); }

// A new page: its HTML, and the game's default CSS for panels (added to the browser defaults, so the page's own CSS wins). Returns 0 if it couldn't be read.
EXPORT("web_load") int32_t web_load(const char* html, int32_t html_len, const char* css, int32_t css_len) {
    doc = litehtml::document::createFromString(litehtml::estring(std::string(html, (size_t)html_len), litehtml::encoding::utf_8),
                                               &container, std::string(litehtml::master_css) + std::string(css, (size_t)css_len));
    return doc ? 1 : 0;
}

// Lays the page out for a panel of this size; returns the height of its content.
EXPORT("web_layout") float web_layout(float width, float height) {
    container.width = width;
    container.height = height;
    if (!doc) {
        return 0;
    }
    doc->media_changed();
    doc->render(width);
    return doc->height();
}

// Draws the page into the display list; returns its length (web_list gives where it is).
EXPORT("web_draw") int32_t web_draw(float scroll_y) {
    list.clear();
    if (doc) {
        litehtml::position clip(0, 0, container.width, container.height);
        doc->draw(0, 0, -scroll_y, &clip);
    }
    return (int32_t)list.size();
}

EXPORT("web_list") const uint8_t* web_list() { return list.data(); }

// The pointer moved, went down or up (kind 0, 1, 2) at a point of the page. Returns 1 if
// what is drawn changed (:hover, :active), so it should be drawn again.
EXPORT("web_pointer") int32_t web_pointer(int32_t kind, float x, float y) {
    if (!doc) {
        return 0;
    }
    bool redraw = false;
    auto mark = [&redraw](const litehtml::position&) { redraw = true; };
    bool changed = kind == 0   ? doc->on_mouse_over(x, y, x, y, mark)
                   : kind == 1 ? doc->on_lbutton_down(x, y, x, y, mark)
                               : doc->on_lbutton_up(x, y, x, y, mark);
    return changed || redraw ? 1 : 0;
}

static void json_str(std::string& out, const char* s) {
    out += '"';
    for (; s && *s; s++) {
        char c = *s;
        if (c == '"' || c == '\\') {
            out += '\\';
            out += c;
        } else if ((unsigned char)c < 0x20) {
            out += ' ';
        } else {
            out += c;
        }
    }
    out += '"';
}

// What a click at a point is on: the nearest element (from the one under the point outwards)
// that a program can tell apart, as JSON {"input", "tag", "id", "name", "event", "value"}; "" if none.
EXPORT("web_hit") int32_t web_hit(float x, float y) {
    scratch.clear();
    if (!doc || !doc->root()) {
        return 0;
    }
    litehtml::element::ptr el = doc->root_render() ? doc->root_render()->get_element_by_point(x, y, x, y, nullptr) : nullptr;
    for (; el; el = el->parent()) {
        const char* tag = el->get_tagName();
        const char* id = el->get_attr("id");
        const char* event = el->get_attr("data-event");
        const char* input = el->get_attr("data-input"); // a text field (the game rewrites <input> as one)
        bool clickable = event || id || input || (tag && (!std::strcmp(tag, "button") || !std::strcmp(tag, "a")));
        if (!clickable) {
            continue;
        }
        scratch = "{\"input\":";
        json_str(scratch, input ? input : "");
        scratch += ",\"tag\":";
        json_str(scratch, tag);
        scratch += ",\"id\":";
        json_str(scratch, id ? id : "");
        scratch += ",\"name\":";
        json_str(scratch, el->get_attr("name", ""));
        scratch += ",\"event\":";
        json_str(scratch, event ? event : "");
        scratch += ",\"value\":";
        json_str(scratch, el->get_attr("value", ""));
        scratch += "}";
        break;
    }
    return (int32_t)scratch.size();
}

EXPORT("web_hit_text") const char* web_hit_text() { return scratch.c_str(); }

// The wheel turned over a point: a box there that scrolls (overflow: auto or scroll) is
// scrolled by dy. Returns how far it moved; 0 means nothing there scrolls (the game then
// scrolls the whole page).
EXPORT("web_scroll") float web_scroll(float dy, float x, float y) {
    if (!doc || !doc->root_render()) {
        return 0;
    }
    float moved = 0;
    for (const auto& sv : doc->on_scroll(0, dy, x, y, x, y)) {
        moved += (float)sv.dy;
    }
    return moved;
}

#ifndef __wasm__
// ---- a native test: fake fonts, print the display list ---------------------------------------
#include <cstdio>
#include <fstream>
#include <iterator>
extern "C" {
int32_t host_font_create(const char*, int32_t, float size, int32_t, int32_t) {
    static std::vector<float> sizes;
    sizes.push_back(size);
    return (int32_t)sizes.size() * 1000 + (int32_t)size;
}
void host_font_metrics(int32_t font, float* out) {
    float size = (float)(font % 1000);
    out[0] = size; out[1] = size * 1.2f; out[2] = size * 0.9f; out[3] = size * 0.3f; out[4] = size * 0.5f; out[5] = size * 0.6f;
}
float host_text_width(int32_t font, const char*, int32_t len) { return (float)(font % 1000) * 0.6f * (float)len; }
void host_image_size(const char*, int32_t, float* out) { out[0] = 16; out[1] = 16; }
}

static float rf(size_t& i) { float v; std::memcpy(&v, &list[i], 4); i += 4; return v; }
static int32_t ri(size_t& i) { int32_t v; std::memcpy(&v, &list[i], 4); i += 4; return v; }

int main(int argc, char** argv) {
    std::string html((std::istreambuf_iterator<char>(*new std::ifstream(argv[1]))), std::istreambuf_iterator<char>());
    web_load(html.data(), (int32_t)html.size(), "", 0);
    float h = web_layout(384, 256);
    std::printf("content height %.1f\n", h);
    int32_t n = web_draw(0);
    for (size_t i = 0; i < (size_t)n;) {
        uint8_t op = list[i++];
        float x = rf(i), y = rf(i), w = rf(i), hh = rf(i);
        std::printf("op %d at %.0f,%.0f %.0fx%.0f", op, x, y, w, hh);
        if (op == OP_TEXT) {
            ri(i); ri(i); ri(i);
            int32_t len = ri(i);
            std::printf(" text '%.*s'", len, (const char*)&list[i]);
            i += (size_t)len;
        } else if (op == OP_FILL) { i += 32; std::printf(" colour %08x", (uint32_t)ri(i)); }
        else if (op == OP_BORDERS) { i += 32 + 4 * 12; }
        else if (op == OP_MARKER) { i += 8; }
        else if (op == OP_IMAGE) { i += 16 + 32; int32_t len = ri(i); i += (size_t)len; }
        else if (op == OP_LINEAR || op == OP_RADIAL) { i += 32 + 16; int32_t s = ri(i); i += (size_t)s * 8; }
        else if (op == OP_CLIP) { i += 32; }
        std::printf("\n");
    }
    std::printf("hit at 60,40: %d %s\n", web_hit(60, 40), web_hit_text());
    return 0;
}
#endif
