# Design Prompt — Pano Host

> Paste everything below the line into Claude **together with the panomc.com screenshot** you
> attached. The screenshot is the source of truth for the visual style; this brief is the source of
> truth for the product and message.

---

You are a senior product & web designer. I've attached a **screenshot of panomc.com**. Design a page
for a new, separate service called **"Pano Host"**. **You decide the layout, sections and structure**
— but the page must (1) clearly explain **Pano** and **Pano Host**, (2) match the visual style of
the attached screenshot, and (3) be built with **Bootstrap 5**.

## Match the attached screenshot's visual identity (do this first)
Study the screenshot and reuse:
- The exact **color palette** (background, surfaces, primary/accent, text, muted), including whether
  it is **light or dark** themed — reproduce the same mood.
- **Typography**: heading + body font feel, weights, sizing, letter-spacing.
- **Components & shapes**: button styles, card/border radius, borders vs. shadows, badges/pills,
  gradients, glows, dividers, icon style.
- **Layout language**: container width, section rhythm/spacing, nav and footer style.
- Any **motifs** (e.g. blocky/Minecraft accents, glassmorphism, subtle gradients, illustrations).

Do **not** invent a new brand. Pano Host must feel like a sibling of panomc.com — same identity,
just signalled as its own service.

## What Pano is
**Pano** is an open-source (GPLv3) advanced web platform that powers **Minecraft server websites**:
themes, a plugin ecosystem (announcements, bans, FAQ, social login, store/market, premium login,
2FA, etc.), an admin panel, a setup wizard, and multi-language support. Today people **self-host** it
(download a JAR and run it with Java + MySQL/MariaDB). The community lives on Discord.

## What "Pano Host" is
**Pano Host** is the **official managed hosting / cloud service for Pano** — so server owners get a
fully-managed Pano site instead of self-hosting. Communicate these points:
- **One-click deploy** — a live Pano site in minutes, no Java/MySQL/server setup.
- **Always up to date** — automatic Pano, theme and plugin updates.
- **Managed everything** — database, automatic **backups**, free **SSL** (Let's Encrypt), uptime,
  DDoS protection, scaling.
- **Plugins & themes** — install from the marketplace in a click.
- **Transparency & support** — real human support and honest, public reliability stats (uptime,
  open support tickets, average resolution time, customer happiness rating).
- **Made by the Pano team** — the people who build Pano run your hosting.

## Positioning: clearly a *separate* service
It must read as "**Pano Host** — a service by Pano", not just another panomc.com page:
- A distinct wordmark lockup: **Pano** + a "Host" suffix/badge (a pill, a slash, or a subtle
  secondary accent) — consistent with the screenshot's type and colors.
- A short tagline framing it as the hosting product, and its own primary call-to-action.
- A small link back to **panomc.com / open-source Pano**, so the relationship is explicit.

## Copy & tone
Write real, concise marketing copy (English, matching the screenshot's tone — confident, modern,
developer-friendly, not corporate). Short punchy headlines, one-line subtext, no lorem ipsum.
