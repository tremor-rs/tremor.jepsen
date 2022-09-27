# tremor.jepsen

This repository adds support to tremor for the Jepsen test harness
for clustered tremor. Currently the source targets the RAFT consensus
layer of tremor which is under development.

The docker environment sets up a jepsen console and 5 nodes for
conducting test runs.

## Prerequisites

All systems
- Ensure `docker` is installed
- Ensure `docker-compose` is installed

On Linux
- There are no known prerequisites apart from git and docker installations.

On Mac OS X
- `brew install bash`
  - so that the `docker/bin/up` and `docker/bin/console` scripts use a
    more up-to-date bash version ( required by jepsen up script ).

## Usage

Jepsen tests for tremor

### Start jepsen environment with 5 logical nodes

Start the docker test environment
```bash
./docker/bin/up --dev
```

### Once 5 nodes have started, run jepsen test phase

```bash
./docker/bin/console
# make the node host keys known to the console machine
rm -rf ~/.ssh/known_hosts
for node in $(echo "n1 n2 n3 n4 n5"); do ssh -oStrictHostKeyChecking=no $node echo "alrighty"; done
# run the jepsen test, use --help to see all the available options
lein run test --rate 100 --concurrency 20 --time-limit 30
```

## License

Copyright 2020-2021, The Tremor Team

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
