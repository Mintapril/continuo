export class CoworkError extends Error {
  constructor(code, message) { super(message); this.name = 'CoworkError'; this.code = code; }
}
export function fail(code, message) { throw new CoworkError(code, message); }
export function check(condition, message, code = 'INVALID_ARGUMENTS') {
  if (!condition) fail(code, message);
}
// Model-visible tool names must match ^[a-zA-Z0-9_-]+$ (OpenAI-compatible providers reject dots).
// Internal/wire names keep their dotted form; only the registered definition name is converted.
export function modelName(name) { return name.replace(/[^a-zA-Z0-9_-]/g, '_'); }
export const str = { type: 'string' };
export const int = { type: 'integer' };
export const bool = { type: 'boolean' };
export function object(properties = {}, required = Object.keys(properties)) {
  return { type: 'object', properties, required, additionalProperties: false };
}
// DSH's supported JSON Schema deliberately omits length/range constraints.
// This validator and each tool's value checks also run in direct execution.
export function validate(schema, value, label = 'arguments') {
  if (schema.type === 'object') {
    check(value !== null && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`);
    check([Object.prototype, null].includes(Object.getPrototypeOf(value)), `${label} must be plain JSON`);
    for (const key of Object.keys(value)) check(Object.hasOwn(schema.properties, key), `${label}.${key} is not allowed`);
    for (const key of schema.required) check(Object.hasOwn(value, key), `${label}.${key} is required`);
    for (const key of Object.keys(value)) validate(schema.properties[key], value[key], `${label}.${key}`);
  } else if (schema.type === 'array') {
    check(Array.isArray(value), `${label} must be an array`);
    for (const item of value) validate(schema.items, item, label);
  } else {
    check(schema.type === 'integer' ? Number.isSafeInteger(value) : typeof value === schema.type, `${label} must be ${schema.type}`);
    if (schema.enum) check(schema.enum.includes(value), `${label} has an unsupported value`);
    if (Object.hasOwn(schema, 'const')) check(value === schema.const, `${label} has an unsupported value`);
  }
  return value;
}
export function boundedString(value, max, label, min = 1) {
  check(typeof value === 'string' && value.length >= min && value.length <= max && !value.includes('\0'), `${label} must contain ${min}..${max} characters without NUL`);
}
export function integer(value, min, max, label) {
  check(Number.isSafeInteger(value) && value >= min && value <= max, `${label} must be an integer ${min}..${max}`);
}
export function textContent(value) { return [{ type: 'text', text: JSON.stringify(value) }]; }
export function tool(name, description, parameters, execute, outputSchema = object({}), render = textContent) {
  return {
    name, description, parameters,
    output: { schema: outputSchema, render: (_args, value) => render(value) },
    async execute(args, exec) { validate(parameters, args); exec?.signal?.throwIfAborted(); return execute(args, exec); },
  };
}
