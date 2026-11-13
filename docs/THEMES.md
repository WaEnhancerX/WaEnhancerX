# WAEX Themes

A WAEX theme is a CSS-like stylesheet, optionally bundled with images, that restyles WhatsApp views.
Install themes from **Styles → Custom Theme CSS → Theme library**, or write CSS directly there.
Restart WhatsApp after changing the active theme.

## Package format

A theme is a `.zip` containing:

```
my-theme.zip
├── style.css        (required)
├── background.png   (optional; png, jpg, webp, gif)
└── font.ttf         (optional)
```

A single wrapping folder inside the zip is accepted. Import rules (enforced when importing):

- `style.css` is required, at most 256 KB, with balanced braces.
- At most 512 files, 32 MB per file, 128 MB total. Assets are flat files (no sub-folders).
- Only image and font files are kept; everything else is ignored. Unsafe paths reject the whole theme.
- Remote `@import` / `url(http…)` are rejected. Bundle images instead.

Themes are stored privately by WAEX and served to WhatsApp through the WAEX provider; no storage
permission is required.

## Header

Metadata goes in a comment at the very top of `style.css`:

```css
/*
name = "Ocean"
author = "Your Name"
version = "1.0"
description = "Blue chat list"
*/
```

## Variables

Define variables in `:root` and use them anywhere with `var(--name, fallback)`:

```css
:root { --accent: #1E88E5; }
#fab { background: var(--accent); }
```

## Selectors

| Pattern | Example |
|---|---|
| ID | `#conversation_row_date` |
| Widget type | `TextView` |
| Activity-scoped | `.com.whatsapp.HomeActivity #fab` |
| Ancestor ID | `#toolbar #title` |
| Pseudo | `TextView:contains(Archived)`, `TextView:nth-child(2)` |

## Properties

Layout: `display` (`none`/`invisible`/`block`), `width`, `height`, `margin*`, `padding*`, `alpha`/`opacity`.
Shape: `border-radius`, `border`, `box-shadow`, `transform` (`rotate`, `scale`).
Color: `background`, `background-color`, `background-image`, `foreground`, `color-tint`, `color-filter`.
Text: `color`, `font-size`, `font-weight`, `font-style`, `text-align`, `text-transform`, `text-decoration`.

Backgrounds and foregrounds accept a color, `linear-gradient(...)`, or `url('image.png')` from the
theme package, with `background-size: cover | contain` (default stretches).

Colors: `#RGB`, `#RRGGBB`, `#AARRGGBB`, Android system names, or Material You `color_system_accent1_100`.
Lengths: `dp`, `sp`, `px` (treated as dp), `%` for width/height.

## Example

```css
/*
name = "Ocean"
author = "WAEX"
*/
:root { --accent: #1E88E5; }

#fab { background: var(--accent); color-tint: #FFFFFF; }
.com.whatsapp.HomeActivity #content { background: url('background.png'); background-size: cover; }
TextView:contains(Archived) { display: none; }
```
