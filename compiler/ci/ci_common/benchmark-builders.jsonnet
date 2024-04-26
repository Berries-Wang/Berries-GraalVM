{
  local c = (import '../../../ci/ci_common/common.jsonnet'),
  local bc = (import '../../../ci/ci_common/bench-common.libsonnet'),
  local cc = (import 'compiler-common.libsonnet'),
  local bench = (import 'benchmark-suites.libsonnet'),
  local hw = bc.bench_hw,

  local main_builds = std.flattenArrays([
    [
<<<<<<< HEAD
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.dacapo + { unicorn_pull_request_benchmarking:: {name: 'libgraal', metrics: ['time']}},
    c.weekly     + hw.x52 + jdk + cc.libgraal + bench.dacapo_size_variants,
    c.weekly     + hw.x52 + jdk + cc.libgraal + bench.dacapo_timing,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.scala_dacapo,
    c.weekly     + hw.x52 + jdk + cc.libgraal + bench.scala_dacapo_size_variants,
    c.weekly     + hw.x52 + jdk + cc.libgraal + bench.scala_dacapo_timing,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.renaissance + {unicorn_pull_request_benchmarking:: 'libgraal'},
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.specjvm2008,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.specjbb2015,
    c.weekly     + hw.x52 + jdk + cc.libgraal + bench.specjbb2015_full_machine,
    c.weekly     + hw.x52 + jdk + cc.libgraal + bench.renaissance_0_11,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.awfy + {unicorn_pull_request_benchmarking:: 'libgraal'},
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.microservice_benchmarks,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.renaissance_legacy,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.micros_graal_whitebox,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.micros_graal_dist,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.micros_misc_graal_dist,
    c.daily      + hw.x52 + jdk + cc.libgraal + bench.micros_shootout_graal_dist,
=======
    c.daily + c.opt_post_merge + hw.e3 + jdk + cc.libgraal + bench.dacapo + PR_bench_libgraal,
    c.daily + c.opt_post_merge + hw.e3 + jdk + cc.libgraal + bench.scala_dacapo + PR_bench_libgraal,
    c.daily + c.opt_post_merge + hw.e3 + jdk + cc.libgraal + bench.renaissance + PR_bench_libgraal,
    c.daily + c.opt_post_merge + hw.e3 + jdk + cc.libgraal + bench.specjvm2008 + PR_bench_libgraal,
    c.on_demand                + hw.e3 + jdk + cc.libgraal + bench.dacapo_size_variants,
    c.on_demand                + hw.e3 + jdk + cc.libgraal + bench.scala_dacapo_size_variants,
    c.monthly                  + hw.e3 + jdk + cc.libgraal + bench.specjbb2015,
    c.daily + c.opt_post_merge + hw.e3 + jdk + cc.libgraal + bench.awfy + PR_bench_libgraal,
    c.daily                    + hw.e3 + jdk + cc.libgraal + bench.microservice_benchmarks,
    c.weekly                   + hw.e3 + jdk + cc.libgraal + bench.micros_graal_whitebox,
    c.weekly                   + hw.e3 + jdk + cc.libgraal + bench.micros_graal_dist,
    c.weekly                   + hw.e3 + jdk + cc.libgraal + bench.micros_misc_graal_dist,
    c.weekly                   + hw.e3 + jdk + cc.libgraal + bench.micros_shootout_graal_dist,
>>>>>>> 77b88ccb9b5 (CI E3 benchmarking migration)
    ]
  for jdk in cc.bench_jdks
  ]),


  local profiling_builds = std.flattenArrays([
    [
    c.monthly + hw.e3 + jdk + cc.libgraal + suite + cc.enable_profiling   + { job_prefix:: "bench-compiler-profiling" },
    c.monthly + hw.e3 + jdk + cc.libgraal + suite + cc.footprint_tracking + { job_prefix:: "bench-compiler-footprint" }
    ]
  for jdk in cc.bench_jdks
  for suite in bench.groups.profiled_suites
  ]),

  local weekly_amd64_forks_builds = std.flattenArrays([
<<<<<<< HEAD
    bc.generate_fork_builds(c.weekly  + hw.x52  + jdk + cc.libgraal + suite, subdir='compiler') +
    bc.generate_fork_builds(c.monthly + hw.x52  + jdk + cc.jargraal + suite, subdir='compiler')
  for jdk in cc.bench_jdks
=======
    bc.generate_fork_builds(c.weekly  + hw.e3  + jdk + cc.libgraal + suite, subdir='compiler') +
    bc.generate_fork_builds(c.monthly + hw.e3  + jdk + cc.jargraal + suite, subdir='compiler')
  for jdk in cc.product_jdks
>>>>>>> 77b88ccb9b5 (CI E3 benchmarking migration)
  for suite in bench.groups.weekly_forks_suites
  ]),

  local weekly_aarch64_forks_builds = std.flattenArrays([
    bc.generate_fork_builds(c.weekly + hw.a12c + cc.latest_jdk + cc.libgraal + suite, subdir='compiler')
  for suite in bench.groups.weekly_forks_suites
  ]),

  local aarch64_builds = [
    c.daily + hw.a12c + cc.latest_jdk + cc.libgraal + suite,
  for suite in bench.groups.all_suites
  ],

  local avx_builds = [
    c.monthly + hw.x82 + jdk + cc.libgraal + avx + suite,
  for avx in [cc.avx2_mode, cc.avx3_mode]
  for jdk in cc.bench_jdks
  for suite in bench.groups.main_suites
  ],

  local zgc_builds = [
<<<<<<< HEAD
    c.weekly + hw.x52 + jdk + cc.libgraal + cc.zgc_mode + suite,
  for jdk in cc.bench_jdks
  for suite in bench.groups.main_suites
=======
    c.weekly + hw.e3 + jdk + cc.libgraal + cc.zgc_mode + suite,
  for jdk in cc.product_jdks
  for suite in bench.groups.main_suites + [bench.specjbb2015]
>>>>>>> 77b88ccb9b5 (CI E3 benchmarking migration)
  ],

  local zgc_avx_builds = [
    c.monthly + hw.x82 + jdk + cc.libgraal + cc.zgc_mode + avx + suite,
  for avx in [cc.avx2_mode, cc.avx3_mode]
  for jdk in cc.bench_jdks
  for suite in bench.groups.main_suites
  ],

  local no_tiered_builds = [
<<<<<<< HEAD
    c.weekly + hw.x52 + jdk + cc.libgraal + cc.no_tiered_comp + suite,
  for jdk in cc.bench_jdks
=======
    c.monthly + hw.e3 + jdk + cc.libgraal + cc.no_tiered_comp + suite,
  for jdk in cc.product_jdks
>>>>>>> 77b88ccb9b5 (CI E3 benchmarking migration)
  for suite in bench.groups.main_suites
  ],

  local no_profile_info_builds = [
<<<<<<< HEAD
    c.weekly + hw.x52 + jdk + cc.libgraal + cc.no_profile_info + suite,
  for jdk in cc.bench_jdks
=======
    c.monthly + hw.e3 + jdk + cc.libgraal + cc.no_profile_info + suite,
  for jdk in cc.product_jdks
>>>>>>> 77b88ccb9b5 (CI E3 benchmarking migration)
  for suite in bench.groups.main_suites
  ],


  local all_builds = main_builds + weekly_amd64_forks_builds + weekly_aarch64_forks_builds + profiling_builds + avx_builds + zgc_builds + zgc_avx_builds + aarch64_builds + no_tiered_builds + no_profile_info_builds,
  local filtered_builds = [b for b in all_builds if b.is_jdk_supported(b.jdk_version) && b.is_arch_supported(b.arch)],
  // adds a "defined_in" field to all builds mentioning the location of this current file
  builds:: [{ defined_in: std.thisFile } + b for b in filtered_builds]
}
