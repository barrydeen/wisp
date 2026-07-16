# Cross-platform meal-plan contract fixtures

Copied from zapcooking/frontend `src/test/fixtures/*.vectors.json`.

| File | sha256 (must match `docs/mealplan-contract.md` on frontend) |
|---|---|
| `ingredient-parser.vectors.json` | `cc7e0ecae3ee8fac51e3ce46502fc758a30c9022b6a3f3765d49c42914b3ab81` |
| `week.vectors.json` | `de5c75e648548f0fe38ebe021a03da17a1f3a977ec7b103642bbf0fd066faf30` |
| `mealplan-schema.vectors.json` | `d8a34927d30a8a636bbc84ed3a384ee5f975d65827ae3302aceffb0239373e18` |
| `grocery-generation.vectors.json` | `090faff2b486886c0c14fe83e3a4fe48f2b3de8ddff375f18f1e5c1d125508d0` |

**Source:** zapcooking/frontend PR #550 (`test/cross-platform-contract-fixtures`), commit `b5a297dfe0fffc53c6d2ef7cca98ca5227b6aff7` (checksums unchanged since extraction; update this SHA when #550 merges if tip moves).

**Rule:** fixture changes require a matching update here; [ContractFixtureChecksumTest] fails on unilateral drift.
