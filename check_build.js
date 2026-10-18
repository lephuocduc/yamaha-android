const https = require('https');

const REPO = 'lephuocduc/yamaha-android';
const runUrl = `https://api.github.com/repos/${REPO}/actions/runs`;

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'MyNMAX-Watcher/1.0' } }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(e);
        }
      });
    }).on('error', reject);
  });
}

async function main() {
  try {
    const runsJson = await fetchJson(runUrl);
    const runs = runsJson.workflow_runs || [];
    if (!runs.length) {
      console.log('No runs found.');
      return;
    }
    const run = runs[0];
    console.log(`[Run #${run.id}] Status: ${run.status} | Conclusion: ${run.conclusion}`);
    
    const jobsJson = await fetchJson(run.jobs_url);
    const jobs = jobsJson.jobs || [];
    for (const job of jobs) {
      console.log(`  Job: ${job.name} -> ${job.status} (${job.conclusion || 'running'})`);
      for (const step of job.steps || []) {
        console.log(`    - ${step.name}: ${step.status} (${step.conclusion || 'in_progress'})`);
      }
    }
  } catch (e) {
    console.error('Error:', e.message);
  }
}

main();
