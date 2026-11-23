use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::{env, fs, process::ExitCode};

#[derive(Deserialize)]
struct Lock {
    version: String,
    artifacts: BTreeMap<String, Artifact>,
    repositories: BTreeMap<String, Vec<String>>,
    dependencies: BTreeMap<String, Vec<String>>,
    #[serde(default)]
    skipped: Vec<String>,
}
#[derive(Deserialize)]
struct Artifact {
    version: String,
    shasums: BTreeMap<String, Option<String>>,
}
#[derive(Serialize)]
struct Jar {
    path: String,
    sha256: String,
    urls: Vec<String>,
}
fn component(s: &str) -> bool {
    !s.is_empty()
        && !s.starts_with('.')
        && !s.ends_with('.')
        && !s.contains("..")
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"._+-".contains(&c))
}
fn plan(lock: Lock, roots: Vec<String>) -> Result<BTreeMap<String, Jar>, String> {
    if lock.version != "3" {
        return Err("expected rules_jvm_external lock version 3".into());
    }
    let mut repositories: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for (url, coords) in &lock.repositories {
        if !url.starts_with("https://") || url.contains('@') || url.contains(['?', '#']) {
            return Err(format!("expected a public HTTPS repository: {url}"));
        }
        for c in coords {
            repositories
                .entry(c)
                .or_default()
                .push(url.trim_end_matches('/'));
        }
    }
    let mut artifacts = BTreeMap::new();
    for (coordinate, artifact) in &lock.artifacts {
        let pieces: Vec<_> = coordinate.split(':').collect();
        let (group, name, ext) = match pieces.as_slice() {
            [g, n] => (*g, *n, "jar"),
            [g, n, e] => (*g, *n, *e),
            _ => return Err(format!("invalid coordinate: {coordinate}")),
        };
        let version = artifact.version.as_str();
        if ![group, name, ext, version].into_iter().all(component) {
            return Err(format!("invalid coordinate or version: {coordinate}"));
        }
        for (classifier, hash) in &artifact.shasums {
            if !component(classifier) {
                return Err(format!("invalid classifier: {classifier}"));
            }
            let key = if classifier == "jar" {
                coordinate.to_owned()
            } else {
                format!("{group}:{name}:{ext}:{classifier}")
            };
            let suffix = if classifier == "jar" {
                String::new()
            } else {
                format!("-{classifier}")
            };
            let path = format!(
                "{}/{name}/{version}/{name}-{version}{suffix}.{ext}",
                group.replace('.', "/")
            );
            artifacts.insert(key, (ext, hash, path));
        }
    }
    let skipped = lock.skipped.into_iter().collect::<BTreeSet<_>>();
    let mut queue: VecDeque<_> = if roots.is_empty() {
        artifacts
            .iter()
            .filter(|(k, (ext, _, _))| {
                *ext == "jar"
                    && !skipped.contains(*k)
                    && !k.ends_with(":sources")
                    && !k.ends_with(":javadoc")
            })
            .map(|(k, _)| k.clone())
            .collect()
    } else {
        roots.into()
    };
    let mut seen = BTreeSet::new();
    let mut result = BTreeMap::new();
    while let Some(key) = queue.pop_front() {
        if !seen.insert(key.clone()) {
            continue;
        }
        let (ext, hash, path) = artifacts
            .get(&key)
            .ok_or_else(|| format!("missing coordinate: {key}"))?;
        if let Some(edges) = lock.dependencies.get(&key) {
            queue.extend(edges.iter().cloned());
        }
        if skipped.contains(&key) {
            continue;
        }
        if *ext != "jar" {
            return Err(format!("non-JAR classpath dependency: {key}"));
        }
        let hash = hash
            .as_ref()
            .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
            .ok_or_else(|| format!("missing SHA-256 for {key}"))?;
        let bases = repositories
            .get(key.as_str())
            .ok_or_else(|| format!("no locked repository for {key}"))?;
        result.insert(
            key,
            Jar {
                path: path.clone(),
                sha256: hash.clone(),
                urls: bases.iter().map(|base| format!("{base}/{path}")).collect(),
            },
        );
    }
    Ok(result)
}
fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let file = args
        .next()
        .ok_or("usage: drvjava-plan LOCKFILE [GROUP:ARTIFACT ...]")?;
    let result = plan(
        serde_json::from_reader(fs::File::open(file)?)?,
        args.collect(),
    )?;
    serde_json::to_writer(std::io::stdout().lock(), &result)?;
    Ok(())
}
fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("drvjava: {e}");
            ExitCode::FAILURE
        }
    }
}
