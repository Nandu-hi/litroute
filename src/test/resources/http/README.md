# Recorded HTTP responses

Real replies from Crossref, OpenAlex and the publishers, captured once by running the live
pipeline over the six sample mails in `../samples/`. `RecordedHttp` replays them by exact URL, so
the enrichment tests run against genuine API payloads without touching the network.

`index.tsv` has one row per response:

    file <TAB> status <TAB> content-type <TAB> real-body-bytes <TAB> stored-bytes <TAB> url

`real-body-bytes` is what the server actually sent; `stored-bytes` is what is in the file. They
differ only where a body was deliberately shortened:

- **Article pages (200)** are cut after `</head>`. `ArticlePageReader` reads nothing but `<meta>`
  tags and one `<link rel=alternate>`, all of which live in the head, and the article text is the
  publisher's, not ours to redistribute.
- **Error replies (403, 404, ...)** are stored empty. They are rejected on status alone and their
  body is never parsed. Most of these are real robot blocks from ascelibrary.org and
  sciencedirect.com - that is genuinely what the pipeline meets there.
- **PDFs** keep their first 8 KiB. The tests check that what was saved really begins with `%PDF-`;
  carrying whole multi-megabyte articles in the repo would serve nothing.

JSON is stored exactly as received, including fields the code ignores.

## Re-recording

The recording is a snapshot: publishers change their markup, and papers gain open-access copies
they did not have. If a test starts failing because the pipeline asks for a URL that was never
recorded, `RecordedHttp` says so by name rather than silently returning a miss. Re-record by
running the pipeline against the live network with a fetcher that writes each reply here.
