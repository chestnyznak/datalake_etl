create or replace view vw_dedup_tbl as
select *
  from (select key1,
               key2,
               key3,
               part_field,
               non_key_field1,
               non_key_field2,
               row_number() over (partition by key1,key2,key3,part_field order by ts_change desc nulls last) row_num
          from external_pqt_table
        ) t
 where t.row_num = 1;