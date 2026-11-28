# Prompt Book navigation: design record

Source: a design session on 2026-11-28. Chris asked for search in the script PDF, bookmarks for
musical numbers and scenes that Claude can populate through the MCP tools, an answer on how
bookmarks overlap a cue stack's dividers, and anything else that helps find a place in a long
script while programming a show.

The page is [`prompt-book-navigation.html`](prompt-book-navigation.html). Its sketch of the view
works: the find field, the rail's headings, the page field and the strip all move the script. The
engineering half is [`../prompt-book-navigation-plan.md`](../prompt-book-navigation-plan.md). Where
the plan and the page disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| How do bookmarks relate to a stack's markers? | **Two records, joined by the book.** A bookmark is the script's and needs no cue or stack; the rail's headings are derived from bookmarks and each cue's anchor; *Add as stack marker* copies one into a stack once. A marker placed on the script being the bookmark, and a marker linked live to its bookmark, were declined. |
| Who reads the script's text? | **The desk.** One index for the view and for Claude, who can then search and place things by quoting a line. The browser reading it alone was declined. |
| Where does Contents live? | **The toolbar's breadcrumb and its list.** A tab in the rail and a left panel were declined. |
| Which of the smaller aids? | **The strip, Go to page and Back.** *What's left*, a mark and filter for cues with nothing programmed, was declined. |
