#standardSQL
  -- Copyright 2017 The Nomulus Authors. All Rights Reserved.
  --
  -- Licensed under the Apache License, Version 2.0 (the "License");
  -- you may not use this file except in compliance with the License.
  -- You may obtain a copy of the License at
  --
  --     http://www.apache.org/licenses/LICENSE-2.0
  --
  -- Unless required by applicable law or agreed to in writing, software
  -- distributed under the License is distributed on an "AS IS" BASIS,
  -- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  -- See the License for the specific language governing permissions and
  -- limitations under the License.

  -- Query that counts the number of real registrars in system.

SELECT
  -- Applies to all TLDs, hence the 'null' magic value.
  STRING(NULL) AS tld,
  'operational-registrars' AS metricName,
  COUNT(registrarName) AS count
FROM
  `%PROJECT_ID%.%DATASTORE_EXPORT_DATA_SET%.%REGISTRAR_TABLE%`
WHERE
  (type = 'REAL' OR type = 'INTERNAL')
GROUP BY metricName
