<template>
  <section class="panel start-panel">
    <div class="start-hero">
      <div>
        <h1>Start</h1>
        <p class="hint">Quick orientation to the admin workspace. Choose a section to begin.</p>
      </div>
      <div class="start-hero-badge">
        <span class="badge neutral">Overview</span>
      </div>
    </div>

    <ul class="start-grid" role="list">
      <li class="start-card">
        <h3>Reclassification Rulesets</h3>
        <p>Author and version the rules used to classify instruments.</p>
        <RouterLink class="ghost" to="/rulesets">Open rulesets</RouterLink>
      </li>
      <li class="start-card">
        <h3>Reclassifications</h3>
        <p>Preview or apply a ruleset to update instrument classifications.</p>
        <RouterLink class="ghost" to="/reclassifications">Open reclassifications</RouterLink>
      </li>
      <li class="start-card">
        <h3>Rebalancer</h3>
        <p>Run allocation rebalancing and inspect the proposed adjustments.</p>
        <RouterLink class="ghost" to="/rebalancer">Open rebalancer</RouterLink>
      </li>
      <li class="start-card">
        <h3>Rebalancer History</h3>
        <p>Review previous rebalancer runs, narratives, and saved snapshots.</p>
        <RouterLink class="ghost" to="/rebalancer/history">Open history</RouterLink>
      </li>
      <li class="start-card">
        <h3>Assessor</h3>
        <p>Assess saving plans and one-time investments for gaps and risk signals.</p>
        <RouterLink class="ghost" to="/assessor">Open assessor</RouterLink>
      </li>
      <li class="start-card">
        <h3>Profile Configuration</h3>
        <p>Manage layer targets, profile settings, and rebalancing constraints.</p>
        <RouterLink class="ghost" to="/layer-targets">Open profiles</RouterLink>
      </li>
      <li class="start-card">
        <h3>Effective Instruments</h3>
        <p>Inspect instrument data with overrides and effective classifications.</p>
        <RouterLink class="ghost" to="/instruments">Open instruments</RouterLink>
      </li>
      <li class="start-card">
        <h3>Knowledge Base</h3>
        <p>Maintain dossiers, extractions, and LLM workflows for coverage.</p>
        <RouterLink class="ghost" to="/knowledge-base">Open Knowledge Base</RouterLink>
      </li>
      <li class="start-card">
        <h3>Savings plans</h3>
        <p>Create, edit, and import savings plan entries for each depot.</p>
        <RouterLink class="ghost" to="/savings-plans">Open savings plans</RouterLink>
      </li>
      <li class="start-card">
        <h3>Imports & Exports</h3>
        <p>Import depot statements and manage full backups, Knowledge Base backups, and overrides.</p>
        <RouterLink class="ghost" to="/imports-exports">Open imports</RouterLink>
      </li>
    </ul>
  </section>
</template>
