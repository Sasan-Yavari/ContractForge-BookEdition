# Chapter resource site

This static site supports *AI Writes Code. You Build the Product.* It contains resource locators and three selected fictional code examples, not the manuscript or private historical evidence.

## Edition and addresses

The index is https://sasan-yavari.github.io/ContractForge-BookEdition/ and English v1.0 uses /1/ through /17/. Those chapter routes stay assigned to this edition. Future editions use a separate prefix such as /v2/1/; never repoint printed addresses to incompatible material. Add an edition selector to the index if needed.

The catalog in `site/edition-v1.json` owns titles, resource labels, source numbers, public URLs and dates. Generated files are not a second source to edit. Code references keep `book-example-v1`, inspected at e9cda411be88a6e84a2e091cb92a1116c6b68b30. Live teaching tasks are separately labeled and do not become part of that tag. Do not move existing tags.

Correct a broken URL only to identify the same cited material, and record a dated material correction here. If material disappears, retain its citation and availability note. Do not substitute a public example for unavailable historical evidence or build another long checklist.

## Build and inspect

Node.js 22 is the only prerequisite. No npm installation or application startup is needed.

```sh
node site/build.mjs
node site/build.mjs --check
python3 -m http.server 8000 --directory site/public
```

Open http://localhost:8000/ to inspect locally. Relative links support both that root and the GitHub project prefix. The builder only rewrites `site/public/`; it checks chapter order, public boundaries, file identity and local navigation/assets. It does not establish external HTTP availability, visual quality or accessibility.

## GitHub Pages

The repository administrator selects **Settings → Pages → Build and deployment → Source → GitHub Actions**. The Chapter resources workflow checks PRs without publishing. Only main can deploy, uploading only `site/public` through the github-pages environment. Inspect that environment's protection settings before activation. If Pages is enabled after merge, run the workflow manually on main.

Confirm the deployment's actual URL, index and all 17 chapter routes, and record the inspected deployment commit and HTTP results in the review tracker. No application/cloud/provider configuration is required. A workflow file alone does not establish that Pages is enabled or the site works.

## QR assets and examples

The first-party encoder in `site/qr.mjs` is limited to QR Model 2 version 4, medium error correction, byte mode and mask 0, with printable ASCII payloads up to 62 characters. SVGs use black modules, white background and a four-module quiet zone. `site/public/qr.json` records exact payloads and module rows for the book renderer; proposed print width is 34 mm. No tracking/expiring QR service or browser-side script is used.

After changing the encoder, payload or rendering, independently decode every SVG and scan actual print-size and ebook-screen samples. Keep the visible address beside the book's QR. Software decoding is not a physical readability check.

The practice checker belongs in the disposable Chapter 3 workspace. It checks exact note lines, not application permissions. Chapter 14's LogQL queries are unexecuted teaching examples requiring the chapter's selected tools, authorized test data and matching fields.

The existing license and distribution/third-party notices retain their scope. The manuscript is not licensed or redistributed by the site. External references remain on their owners' sites.
