# Putting the app online — the long version

The README has the short version. This is the same thing with every screen
described, for doing it on a phone with no prior experience.

You need: a phone with Chrome, and the GitHub login that owns
`GapperGames/AppTest`. Nothing else. No computer, no card, no downloads.

It takes about three minutes, and most of that is waiting.

---

## What you're actually doing

The code sits on GitHub. GitHub stores code; it doesn't run it. Netlify is a
free service that takes code from GitHub and *runs* it at a web address.

So: you're introducing Netlify to GitHub, pointing it at this repo, and telling
it which branch to use. That's the whole job. Netlify reads `netlify.toml` in
the repo and works out the rest itself.

---

## The steps

**1.** Open Chrome and go to **app.netlify.com/start**

**2.** You'll see a signup screen with a few buttons — GitHub, GitLab, Bitbucket,
email. Tap **GitHub**.

**3.** GitHub asks you to log in (if you aren't already), then shows a green
**Authorize Netlify** button. Tap it. This lets Netlify see your repos; it's
the normal thing every host does.

**4.** GitHub may then ask **which** repositories Netlify can see, with two
options — all repositories, or select ones. Either is fine. If you pick
*Only select repositories*, you must tick **AppTest** in the list underneath,
or Netlify won't find it in the next step. Tap **Install** / **Save**.

**5.** Netlify shows a list of your repos. Tap **AppTest**.

**6.** Now the setup screen. This is the only one that needs care.

   Find the field labelled **Branch to deploy**. It'll be showing something
   else by default. Tap it, and choose:

   ```
   claude/music-chords-webapp-dt8dai
   ```

   It's a dropdown; there are only four branches, so it's a short list.

   Leave **Build command** and **Publish directory** exactly as they are —
   empty, or already filled in. Don't type anything into them. `netlify.toml`
   sets those, and typing over it is the one way to break this.

**7.** Tap **Deploy** (the button may say *Deploy AppTest* or *Deploy site*).

**8.** You'll get a page with a log scrolling past. Wait. It takes 30–60
seconds. When it finishes it says **Published** or **Site is live**, and shows
a web address like:

   ```
   graceful-otter-4d2f81.netlify.app
   ```

   That address *is* the app. Tap it.

**9.** Optional: the random name is silly. **Site configuration → Change site
name** lets you make it something else. Doesn't affect anything.

**10.** With the app open in Chrome, tap **⋮** (top right) → **Add to Home
screen**. Now it's an icon like any other app, and opens fullscreen with no
browser bar.

---

## Then check it works

Search for a song and open it.

If anything fails, the error screen has a **Run a check** button. Tap it. It
tells you which of the possible causes it actually is, in plain words, rather
than making you guess. You can also go straight to `your-address/api/health`.

The one genuinely uncertain thing is whether Ultimate Guitar will accept
requests from Netlify's servers — see *The one real risk* in the README. The
health check distinguishes that from every other failure, which is exactly why
it's there.

---

## If a screen doesn't match

Netlify redesigns this flow every so often, so wording may drift. The shape
never does. Whatever the screen says, you are always looking for:

- somewhere to connect **GitHub**
- somewhere to pick the **AppTest** repo
- somewhere to set the **branch** to `claude/music-chords-webapp-dt8dai`
- a **Deploy** button

If you get stuck, the useful thing to report is what the screen is *called* and
what buttons it offers.

## If you'd rather use Vercel

Same idea, at **vercel.com/new**. The catch is that Vercel doesn't let you
choose a branch during setup — it deploys the repo's default branch, so
afterwards you go to **Settings → Git → Production Branch**, set it to
`claude/music-chords-webapp-dt8dai`, and redeploy. That extra step after the
fact is the only reason Netlify is the recommendation.
