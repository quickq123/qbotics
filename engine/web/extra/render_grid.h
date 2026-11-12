// Qbotics' addition to litehtml: CSS grid, the common part (see render_grid.cpp).
#ifndef LITEHTML_RENDER_GRID_H
#define LITEHTML_RENDER_GRID_H

#include "render_flex.h"

namespace litehtml
{
    // A grid container. Internally it is a flex container (so its children are treated as flex
    // items are: blockified, wrapped in anonymous boxes), laid out as a grid instead.
    class render_item_grid : public render_item_flex
    {
        pixel_t _render_content(pixel_t x, pixel_t y, bool second_pass, const containing_block_context& self_size,
                                formatting_context* fmt_ctx) override;

      public:
        explicit render_item_grid(std::shared_ptr<element> src_el) :
            render_item_flex(std::move(src_el))
        {
        }

        std::shared_ptr<render_item> clone() override
        {
            return std::make_shared<render_item_grid>(src_el());
        }
    };
} // namespace litehtml

#endif // LITEHTML_RENDER_GRID_H
