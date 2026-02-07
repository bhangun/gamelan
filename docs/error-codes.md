# Gamelan Error Codes

Generated from `ErrorCode` at build time.

## WORKFLOW

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| WORKFLOW_001 | 404 | false | Workflow not found |
| WORKFLOW_002 | 400 | false | Invalid workflow definition |
| WORKFLOW_003 | 404 | false | Workflow version not found |
| WORKFLOW_004 | 409 | false | Workflow already exists |
| WORKFLOW_005 | 409 | false | Invalid workflow state |

## RUN

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| RUN_001 | 404 | false | Workflow run not found |
| RUN_002 | 409 | false | Invalid workflow run state |
| RUN_003 | 409 | false | Workflow run already terminal |
| RUN_004 | 409 | false | Compensation state is not initialized |

## TASK

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| TASK_001 | 404 | false | Task execution not found |
| TASK_002 | 502 | true | Task dispatch failed |
| TASK_003 | 400 | false | Task validation failed |
| TASK_004 | 503 | true | Executor unavailable |

## DISPATCH

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| DISPATCH_001 | 400 | false | No suitable dispatcher found |
| DISPATCH_002 | 400 | false | Dispatch request invalid |
| DISPATCH_003 | 504 | true | Dispatch timeout |
| DISPATCH_004 | 502 | true | Dispatch response invalid |

## SCHEDULER

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| SCHEDULER_001 | 503 | true | No executor available |
| SCHEDULER_002 | 429 | true | Concurrency limit exceeded |

## STORAGE

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| STORAGE_001 | 500 | true | Failed to read from storage |
| STORAGE_002 | 500 | true | Failed to write to storage |
| STORAGE_003 | 500 | true | Failed to serialize/deserialize data |

## PLUGIN

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| PLUGIN_001 | 404 | false | Plugin not found |
| PLUGIN_002 | 500 | false | Plugin initialization failed |
| PLUGIN_003 | 500 | true | Plugin start failed |
| PLUGIN_004 | 500 | true | Plugin stop failed |

## SECURITY

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| SECURITY_001 | 401 | false | Tenant not found |
| SECURITY_002 | 403 | false | Unauthorized tenant access |
| SECURITY_003 | 401 | false | Invalid token |
| SECURITY_004 | 403 | false | Invalid signature |

## VALIDATION

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| VALIDATION_001 | 400 | false | Validation failed |
| VALIDATION_002 | 400 | false | Required field missing |

## CONFIG

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| CONFIG_001 | 500 | false | Required configuration missing |
| CONFIG_002 | 500 | false | Invalid configuration value |

## CONCURRENCY

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| CONCURRENCY_001 | 504 | true | Failed to acquire lock |
| CONCURRENCY_002 | 409 | true | Concurrency conflict |

## RUNTIME

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| RUNTIME_001 | 500 | true | Runtime execution failed |

## INTERNAL

| Code | HTTP | Retryable | Message |
| --- | --- | --- | --- |
| INTERNAL_001 | 500 | true | Internal server error |

