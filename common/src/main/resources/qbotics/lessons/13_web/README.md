# Lesson 13: Web Developer

**Earns:** *Web Developer*: a program reacts to a player using a Web Panel's page. **You need:**
**Web Panels** on the computer's cables (3 wide and 2 high is a good size), and the `store`.

## The idea

A **Web Panel** shows a page written in HTML and CSS, like a small website. The program fills
the page with its **state** (a dictionary), and buttons and text fields on the page send events
back to the program. Players right-click to click; sneak and right-click with an empty hand to
scroll and type.

## Try it

Make two files in this lesson's folder (`cd /lessons/13_web`). First the page: `edit index.html`.
`{{total}}` is filled from the program's state:

    <html><body style="color: white; background: #224">
      <h1>The store</h1>
      <p>It holds {{total}} items.</p>
      <button data-event="refresh">Count again</button>
    </body></html>

Then the program: `edit page.py`, and `run page.py`.

    from logistics import network
    import events

    net = network()
    web = net.web_panel()
    store = net.bus("store")

    def count(event=None):
        web.state.update(total=store.count())

    web.on("refresh", count)        # the button's data-event
    web.show("/lessons/13_web/index.html")  # the page
    count()
    events.run()

## Your turn

Add a text field that searches the store: `<input name="search" value="{{search}}">` sends an
event named `search` with `event.value`, the text typed. Count only what matches:
`store.count("*" + event.value + "*")`, and put the result in the state.

## New Python here

- `web.state.update(total=..., search=...)` hands over several named values at once.
- HTML is not Python: it is the page's own language, of **tags** like `<p>text</p>`. `{{total}}`
  is filled in from the program's state.
- A file can be read from anywhere by its whole path: `/lessons/13_web/index.html`.

## Hints

- `{{#items}} ... {{/items}}` repeats for each item of a list in the state: a table of stock.
- `<img src="item:minecraft:diamond">` shows an item's picture.
- Pages can have a stylesheet (`<link rel="stylesheet" href="style.css">`) and JavaScript.

**Next:** `cd /lessons/14_tablet`. **More:** `/helpme/displays/web_storage`, `/helpme/displays/web_dashboard`.
**Manual:** section 7c.5b.
