"""Read-only deterministic MySQL table/FK fingerprints; values never leave query callers."""
import hashlib
import re


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_]+', value):
        raise ValueError('Unsupported database identifier')
    return '`' + value + '`'


def literal_grant_pattern(schema, partial_revokes):
    identifier(schema)
    if str(partial_revokes).upper() not in ('0', '1', 'OFF', 'ON'):
        raise ValueError('Unknown MySQL partial_revokes setting')
    return schema if str(partial_revokes).upper() in ('1', 'ON') else schema.replace('_', r'\_')


def literal_grant_identifier(schema, partial_revokes):
    return '`' + literal_grant_pattern(schema, partial_revokes) + '`'


def database_fingerprint(query, schema, row_digest):
    """query returns metadata/count strings; row_digest streams raw batch row output."""
    identifier(schema)
    names = query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema +
                  "' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;").splitlines()
    if not names or len(names) != len(set(names)):
        raise ValueError('Restored database has no unique base tables')
    tables = []
    for table in names:
        identifier(table)
        columns = query("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema +
                        "' AND TABLE_NAME='" + table + "' ORDER BY ORDINAL_POSITION;").splitlines()
        keys = query("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='" + schema +
                     "' AND TABLE_NAME='" + table + "' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION;").splitlines()
        if not columns:
            raise ValueError('A base table has no columns')
        cells = ["IF(" + identifier(column) + " IS NULL,'N',CONCAT('V',HEX(CAST(" + identifier(column) + " AS BINARY))))"
                 for column in columns]
        order = ','.join(map(identifier, keys)) if keys else ','.join(cells)
        sql = 'SELECT ' + ','.join(cells) + ' FROM ' + identifier(schema) + '.' + identifier(table) + ' ORDER BY ' + order + ';'
        ddl = query('SHOW CREATE TABLE ' + identifier(schema) + '.' + identifier(table) + ';')
        normalized = ddl.replace(identifier(schema) + '.', '`__snapshot__`.')
        tables.append({'table': table, 'rows': int(query('SELECT COUNT(*) FROM ' + identifier(schema) + '.' + identifier(table) + ';')),
                       'rowSha256': row_digest(sql), 'ddlSha256': hashlib.sha256(normalized.encode()).hexdigest()})
    fk_rows = query("SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_SCHEMA,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME "
                    "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='" + schema +
                    "' AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION;")
    groups = {}
    for row in fk_rows.splitlines():
        table, constraint, column, parent_schema, parent, parent_column = row.split('\t')
        if parent_schema != schema or table not in names or parent not in names:
            raise ValueError('Foreign keys must reference only this restored schema')
        for item in (table, constraint, column, parent, parent_column):
            identifier(item)
        groups.setdefault((table, constraint, parent), []).append((column, parent_column))
    foreign = []
    for (table, constraint, parent), pairs in groups.items():
        required = ' AND '.join('c.' + identifier(column) + ' IS NOT NULL' for column, _ in pairs)
        joined = ' AND '.join('p.' + identifier(parent_column) + '=c.' + identifier(column) for column, parent_column in pairs)
        sql = 'SELECT COUNT(*) FROM ' + identifier(schema) + '.' + identifier(table) + ' c WHERE ' + required + \
              ' AND NOT EXISTS (SELECT 1 FROM ' + identifier(schema) + '.' + identifier(parent) + ' p WHERE ' + joined + ');'
        if query(sql) != '0':
            raise ValueError('A restored foreign key has orphaned rows')
        foreign.append({'table': table, 'constraint': constraint, 'parent': parent, 'columns': [list(pair) for pair in pairs]})
    return {'format': 1, 'tableCount': len(tables), 'tables': tables, 'foreignKeys': foreign,
            'foreignKeyConstraintsChecked': len(foreign), 'foreignKeyViolations': 0,
            'foreignKeysReferenceOnlySnapshotSchema': True}


def require_same_fingerprint(expected, actual):
    if expected != actual:
        raise ValueError('All-table row counts, DDL/row SHA or foreign key definitions differ')
