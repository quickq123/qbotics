# Lesson 13: Web Developer

**Earns:** *Web Developer*: a program reacts to a player using a Web Panel's page. **You need:**
**Web Panels** on the computer's cables (3 wide and 2 high is a good size), and the `store`.

## The idea

A **Web Panel** shows a page written in HTML and CSS, like a small website. The program fills
the page with its **state** (a dictionary), and buttons and text fields on the page send events
back to the program. Players right-click to click; sneak and right-click with an empty hand to
scroll and type.

## Try it

Make a folder with two files. First the page, `/my/web/index.html`. `{{name}}` is filled from
the state:

    <html><body style="color: white; background: #224">
      <h1>The store</h1>
      <p>It holds {{total}} items.</p>
      <button data-event="refresh">Count again</button>
    </body></html>

Then the program, `/my/web/page.py`:

    from logistics import network
    import events

    net = network()
    web = net.web_panel()
    store = net.bus("store")

    def count(event=None):
        web.state.update(total=store.count())

    web.on("refresh", count)        # the button's data-event
    web.show("/my/web/index.html")  # the page, from the drive
    count()
    events.run()

## Your turn

Add a text field that searches the store: `<input name="search" value="{{search}}">` sends an
event named `search` with `event.value`, the text typed. Count only what matches:
`store.count("*" + event.value + "*")`, and put the result in the state.

## Hints

- `{{#items}} ... {{/items}}` repeats for each item of a list in the state: a table of stock.
- `<img src="item:minecraft:diamond">` shows an item's picture.
- Pages can have a stylesheet (`<link rel="stylesheet" href="style.css">`) and JavaScript.

**Next:** `14_tablet`. **More:** `/helpme/displays/web_storage`, `/helpme/displays/web_dashboard`.
**Manual:** section 7c.5b.
